// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.syntax;

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.util.SpellChecker;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A class for doing static checks on files, before evaluating them.
 *
 * <p>We implement the semantics discussed in
 * https://github.com/bazelbuild/proposals/blob/master/docs/2018-06-18-name-resolution.md
 *
 * <p>When a variable is defined, it is visible in the entire block. For example, a global variable
 * is visible in the entire file; a variable in a function is visible in the entire function block
 * (even on the lines before its first assignment).
 *
 * <p>Validation is a mutation of the syntax tree, as it attaches scope information to Identifier
 * nodes. (In the future, it will attach additional information to functions to support lexical
 * scope, and even compilation of the trees to bytecode.) Validation errors are reported in the
 * analogous manner to scan/parse errors: for a StarlarkFile, they are appended to {@code
 * StarlarkFile.errors}; for an expression they will be [TODO(adonovan): implement] reported by an
 * SyntaxError exception. It is legal to validate a file that already contains scan/parse errors,
 * though it may lead to secondary validation errors.
 */
// TODO(adonovan): make this class private. Call it through the EvalUtils facade.
public final class ValidationEnvironment extends NodeVisitor {

  enum Scope {
    /** Symbols defined inside a function or a comprehension. */
    Local("local"),
    /** Symbols defined at a module top-level, e.g. functions, loaded symbols. */
    Module("global"),
    /** Predefined symbols (builtins) */
    Universe("builtin");

    private final String qualifier;

    private Scope(String qualifier) {
      this.qualifier = qualifier;
    }

    String getQualifier() {
      return qualifier;
    }
  }

  private static class Block {
    private final Set<String> variables = new HashSet<>();
    private final Scope scope;
    @Nullable private final Block parent;

    Block(Scope scope, @Nullable Block parent) {
      this.scope = scope;
      this.parent = parent;
    }
  }

  private final List<Event> errors;
  private final StarlarkThread thread;
  private Block block;
  private int loopCount;
  /** In BUILD files, we have a slightly different behavior for legacy reasons. */
  // TODO(adonovan): eliminate isBuildFile. It is necessary because the prelude is implemented
  // by inserting shared Statements, which must not be mutated, into each StarlarkFile.
  // Instead, we should implement the prelude by executing it like a .bzl module
  // and putting its members in the initial environment of the StarlarkFile.
  private final boolean isBuildFile;

  private ValidationEnvironment(List<Event> errors, StarlarkThread thread, boolean isBuildFile) {
    Preconditions.checkArgument(thread.isGlobal());
    this.errors = errors;
    this.thread = thread;
    this.isBuildFile = isBuildFile;
    block = new Block(Scope.Universe, null);
    Set<String> builtinVariables = thread.getVariableNames();
    block.variables.addAll(builtinVariables);
  }

  void addError(Location loc, String message) {
    errors.add(Event.error(loc, message));
  }

  /**
   * First pass: add all definitions to the current block. This is done because symbols are
   * sometimes used before their definition point (e.g. a functions are not necessarily declared in
   * order).
   */
  private void collectDefinitions(Iterable<Statement> stmts) {
    for (Statement stmt : stmts) {
      collectDefinitions(stmt);
    }
  }

  private void collectDefinitions(Statement stmt) {
    switch (stmt.kind()) {
      case ASSIGNMENT:
        collectDefinitions(((AssignmentStatement) stmt).getLHS());
        break;
      case AUGMENTED_ASSIGNMENT:
        collectDefinitions(((AugmentedAssignmentStatement) stmt).getLHS());
        break;
      case IF:
        IfStatement ifStmt = (IfStatement) stmt;
        collectDefinitions(ifStmt.getThenBlock());
        if (ifStmt.getElseBlock() != null) {
          collectDefinitions(ifStmt.getElseBlock());
        }
        break;
      case FOR:
        ForStatement forStmt = (ForStatement) stmt;
        collectDefinitions(forStmt.getLHS());
        collectDefinitions(forStmt.getBlock());
        break;
      case FUNCTION_DEF:
        Identifier fctName = ((DefStatement) stmt).getIdentifier();
        declare(fctName.getName(), fctName.getLocation());
        break;
      case LOAD:
        LoadStatement load = (LoadStatement) stmt;

        // The global reassignment check is not yet enabled for BUILD files,
        // but we apply it to load statements as a special case.
        // Because (for now) its error message is better than the general
        // message emitted by 'declare', we'll apply it to non-BUILD files too.
        Set<String> names = new HashSet<>();
        for (LoadStatement.Binding b : load.getBindings()) {
          if (!names.add(b.getLocalName().getName())) {
            addError(
                b.getLocalName().getLocation(),
                String.format(
                    "load statement defines '%s' more than once", b.getLocalName().getName()));
          }
        }

        for (LoadStatement.Binding b : load.getBindings()) {
          declare(b.getLocalName().getName(), b.getLocalName().getLocation());
        }
        break;
      case EXPRESSION:
      case FLOW:
      case RETURN:
        // nothing to declare
    }
  }

  private void collectDefinitions(Expression lhs) {
    for (Identifier id : Identifier.boundIdentifiers(lhs)) {
      declare(id.getName(), id.getLocation());
    }
  }

  private void assign(Expression lhs) {
    if (lhs instanceof Identifier) {
      if (!isBuildFile) {
        ((Identifier) lhs).setScope(block.scope);
      }
      // no-op
    } else if (lhs instanceof IndexExpression) {
      visit(lhs);
    } else if (lhs instanceof ListExpression) {
      for (Expression elem : ((ListExpression) lhs).getElements()) {
        assign(elem);
      }
    } else {
      addError(lhs.getLocation(), "cannot assign to '" + lhs + "'");
    }
  }

  @Override
  public void visit(Identifier node) {
    @Nullable Block b = blockThatDefines(node.getName());
    if (b == null) {
      // The identifier might not exist because it was restricted (hidden) by the current semantics.
      // If this is the case, output a more helpful error message than 'not found'.
      FlagGuardedValue result = thread.getRestrictedBindings().get(node.getName());
      addError(
          node.getLocation(),
          result != null
              ? result.getErrorFromAttemptingAccess(thread.getSemantics(), node.getName())
              : createInvalidIdentifierException(node.getName(), getAllSymbols()));
      return;
    }
    // TODO(laurentlb): In BUILD files, calling setScope will throw an exception. This happens
    // because some AST nodes are shared across multipe ASTs (due to the prelude file).
    if (!isBuildFile) {
      node.setScope(b.scope);
    }
  }

  // This is exposed to Eval until validation becomes a precondition for evaluation.
  static String createInvalidIdentifierException(String name, Set<String> candidates) {
    if (name.equals("$error$")) {
      return "contains syntax error(s)";
    }

    String error = getErrorForObsoleteThreadLocalVars(name);
    if (error != null) {
      return error;
    }

    String suggestion = SpellChecker.didYouMean(name, candidates);
    return "name '" + name + "' is not defined" + suggestion;
  }

  static String getErrorForObsoleteThreadLocalVars(String name) {
    if (name.equals("PACKAGE_NAME")) {
      return "The value 'PACKAGE_NAME' has been removed in favor of 'package_name()', "
          + "please use the latter ("
          + "https://docs.bazel.build/versions/master/skylark/lib/native.html#package_name). ";
    }
    if (name.equals("REPOSITORY_NAME")) {
      return "The value 'REPOSITORY_NAME' has been removed in favor of 'repository_name()', please"
          + " use the latter ("
          + "https://docs.bazel.build/versions/master/skylark/lib/native.html#repository_name).";
    }
    return null;
  }

  @Override
  public void visit(ReturnStatement node) {
    if (block.scope != Scope.Local) {
      addError(node.getLocation(), "return statements must be inside a function");
    }
    super.visit(node);
  }

  @Override
  public void visit(ForStatement node) {
    if (block.scope != Scope.Local) {
      addError(
          node.getLocation(),
          "for loops are not allowed at the top level. You may move it inside a function "
              + "or use a comprehension, [f(x) for x in sequence]");
    }
    loopCount++;
    visit(node.getCollection());
    assign(node.getLHS());
    visitBlock(node.getBlock());
    Preconditions.checkState(loopCount > 0);
    loopCount--;
  }

  @Override
  public void visit(LoadStatement node) {
    if (block.scope == Scope.Local) {
      addError(node.getLocation(), "load statement not at top level");
    }
    super.visit(node);
  }

  @Override
  public void visit(FlowStatement node) {
    if (node.getKind() != TokenKind.PASS && loopCount <= 0) {
      addError(
          node.getLocation(), node.getKind().toString() + " statement must be inside a for loop");
    }
    super.visit(node);
  }

  @Override
  public void visit(DotExpression node) {
    visit(node.getObject());
    // Do not visit the field.
  }

  @Override
  public void visit(Comprehension node) {
    openBlock(Scope.Local);
    for (Comprehension.Clause clause : node.getClauses()) {
      if (clause instanceof Comprehension.For) {
        Comprehension.For forClause = (Comprehension.For) clause;
        collectDefinitions(forClause.getVars());
      }
    }
    // TODO(adonovan): opt: combine loops
    for (Comprehension.Clause clause : node.getClauses()) {
      if (clause instanceof Comprehension.For) {
        Comprehension.For forClause = (Comprehension.For) clause;
        visit(forClause.getIterable());
        assign(forClause.getVars());
      } else {
        Comprehension.If ifClause = (Comprehension.If) clause;
        visit(ifClause.getCondition());
      }
    }
    visit(node.getBody());
    closeBlock();
  }

  @Override
  public void visit(DefStatement node) {
    if (block.scope == Scope.Local) {
      addError(
          node.getLocation(),
          "nested functions are not allowed. Move the function to the top level.");
    }
    for (Parameter<Expression, Expression> param : node.getParameters()) {
      if (param.isOptional()) {
        visit(param.getDefaultValue());
      }
    }
    openBlock(Scope.Local);
    for (Parameter<Expression, Expression> param : node.getParameters()) {
      if (param.hasName()) {
        declare(param.getName(), param.getLocation());
      }
    }
    collectDefinitions(node.getStatements());
    visitAll(node.getStatements());
    closeBlock();
  }

  @Override
  public void visit(IfStatement node) {
    if (block.scope != Scope.Local) {
      addError(
          node.getLocation(),
          "if statements are not allowed at the top level. You may move it inside a function "
              + "or use an if expression (x if condition else y).");
    }
    super.visit(node);
  }

  @Override
  public void visit(AssignmentStatement node) {
    visit(node.getRHS());
    assign(node.getLHS());
  }

  @Override
  public void visit(AugmentedAssignmentStatement node) {
    if (node.getLHS() instanceof ListExpression) {
      addError(
          node.getLocation(), "cannot perform augmented assignment on a list or tuple expression");
    }
    // Other bad cases are handled in assign.
    visit(node.getRHS());
    assign(node.getLHS());
  }

  /** Declare a variable and add it to the environment. */
  private void declare(String varname, Location location) {
    // TODO(laurentlb): Forbid reassignment in BUILD files.
    if (!isBuildFile && block.scope == Scope.Module && block.variables.contains(varname)) {
      // Symbols defined in the module scope cannot be reassigned.
      //
      // TODO(adonovan): make error message more precise: "x reassigned at top level"
      // and emit a secondary error "x previously declared here". This requires an
      // upcoming changes to report events not exceptions.
      addError(
          location,
          String.format(
              "Variable %s is read only (read more at %s)",
              varname,
              "https://bazel.build/versions/master/docs/skylark/errors/read-only-variable.html"));
    }
    block.variables.add(varname);
  }

  /** Returns the nearest Block that defines a symbol. */
  private Block blockThatDefines(String varname) {
    for (Block b = block; b != null; b = b.parent) {
      if (b.variables.contains(varname)) {
        return b;
      }
    }
    return null;
  }

  /** Returns the set of all accessible symbols (both local and global) */
  private Set<String> getAllSymbols() {
    Set<String> all = new HashSet<>();
    for (Block b = block; b != null; b = b.parent) {
      all.addAll(b.variables);
    }
    return all;
  }

  // Report an error if a load statement appears after another kind of statement.
  private void checkLoadAfterStatement(List<Statement> statements) {
    Location firstStatement = null;

    for (Statement statement : statements) {
      // Ignore string literals (e.g. docstrings).
      if (statement instanceof ExpressionStatement
          && ((ExpressionStatement) statement).getExpression() instanceof StringLiteral) {
        continue;
      }

      if (statement instanceof LoadStatement) {
        if (firstStatement == null) {
          continue;
        }
        addError(
            statement.getLocation(),
            "load() statements must be called before any other statement. "
                + "First non-load() statement appears at "
                + firstStatement
                + ". Use --incompatible_bzl_disallow_load_after_statement=false to temporarily "
                + "disable this check.");
      }

      if (firstStatement == null) {
        firstStatement = statement.getLocation();
      }
    }
  }

  private void validateToplevelStatements(List<Statement> statements) {
    // Check that load() statements are on top.
    if (!isBuildFile && thread.getSemantics().incompatibleBzlDisallowLoadAfterStatement()) {
      checkLoadAfterStatement(statements);
    }

    openBlock(Scope.Module);

    // Add each variable defined by statements, not including definitions that appear in
    // sub-scopes of the given statements (function bodies and comprehensions).
    collectDefinitions(statements);

    // Second pass: ensure that all symbols have been defined.
    visitAll(statements);
    closeBlock();
  }

  /**
   * Performs static checks, including name resolution on {@code file}, which is mutated. The {@code
   * StarlarkThread} provides the global names and the StarlarkSemantics. Errors are appended to
   * {@link StarlarkFile#errors}.
   */
  // TODO(adonovan): replace thread by Set<String> and StarlarkSemantics.
  public static void validateFile(StarlarkFile file, StarlarkThread thread, boolean isBuildFile) {
    ValidationEnvironment venv = new ValidationEnvironment(file.errors, thread, isBuildFile);
    if (thread.getSemantics().incompatibleRestrictStringEscapes()) {
      file.addStringEscapeEvents();
    }
    venv.validateToplevelStatements(file.getStatements());
    // Check that no closeBlock was forgotten.
    Preconditions.checkState(venv.block.parent == null);
  }

  /** Open a new lexical block that will contain the future declarations. */
  private void openBlock(Scope scope) {
    block = new Block(scope, block);
  }

  /** Close a lexical block (and lose all declarations it contained). */
  private void closeBlock() {
    block = Preconditions.checkNotNull(block.parent);
  }

}
