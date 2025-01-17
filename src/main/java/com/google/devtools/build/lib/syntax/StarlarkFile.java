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

import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.Parser.ParseResult;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Syntax tree for a Starlark file, such as a Bazel BUILD or .bzl file.
 *
 * <p>Call {@link #parse} to parse a file. Parser errors are recorded in the syntax tree (see {@link
 * #errors}), which may be incomplete.
 */
public final class StarlarkFile extends Node {

  private final ImmutableList<Statement> statements;
  private final ImmutableList<Comment> comments;
  final List<Event> errors; // appended to by ValidationEnvironment
  private final List<Event> stringEscapeEvents;
  @Nullable private final String contentHashCode;

  private StarlarkFile(
      ImmutableList<Statement> statements,
      List<Event> errors,
      String contentHashCode,
      Location location,
      ImmutableList<Comment> comments,
      List<Event> stringEscapeEvents) {
    this.statements = statements;
    this.comments = comments;
    this.errors = errors;
    this.stringEscapeEvents = stringEscapeEvents;
    this.contentHashCode = contentHashCode;
    this.setLocation(location);
  }

  private static StarlarkFile create(
      List<Statement> preludeStatements,
      ParseResult result,
      String contentHashCode,
      boolean allowImportInternal) {
    ImmutableList.Builder<Statement> statementsbuilder =
        ImmutableList.<Statement>builder().addAll(preludeStatements);

    if (allowImportInternal) {
      for (Statement stmt : result.statements) {
        if (stmt instanceof LoadStatement) {
          statementsbuilder.add(LoadStatement.allowLoadingOfInternalSymbols((LoadStatement) stmt));
        } else {
          statementsbuilder.add(stmt);
        }
      }
    } else {
      statementsbuilder.addAll(result.statements);
    }
    ImmutableList<Statement> statements = statementsbuilder.build();
    return new StarlarkFile(
        statements,
        result.errors,
        contentHashCode,
        result.location,
        ImmutableList.copyOf(result.comments),
        result.stringEscapeEvents);
  }

  /**
   * Extract a subtree containing only statements from {@code firstStatement} (included) up to
   * {@code lastStatement} excluded.
   */
  public StarlarkFile subTree(int firstStatement, int lastStatement) {
    ImmutableList<Statement> statements = this.statements.subList(firstStatement, lastStatement);
    return new StarlarkFile(
        statements,
        errors,
        null,
        this.statements.get(firstStatement).getLocation(),
        ImmutableList.of(),
        stringEscapeEvents);
  }

  /**
   * Returns an unmodifiable view of the list of scanner, parser, and (perhaps) resolver errors
   * accumulated in this Starlark file.
   */
  public List<Event> errors() {
    return Collections.unmodifiableList(errors);
  }

  /** Returns errors().isEmpty(). */
  public boolean ok() {
    return errors.isEmpty();
  }

  /**
   * Appends string escaping errors to {@code errors}. The Lexer diverts such errors into a separate
   * bucket as they should be selectively reported depending on a StarlarkSemantics, to which the
   * lexer/parser does not have access. This function is called by ValidationEnvironment, which has
   * access to a StarlarkSemantics and can thus decide whether to respect or ignore these events.
   *
   * <p>Naturally this function should be called at most once.
   */
  void addStringEscapeEvents() {
    errors.addAll(stringEscapeEvents);
  }

  /** Returns an (immutable, ordered) list of statements in this BUILD file. */
  public ImmutableList<Statement> getStatements() {
    return statements;
  }

  /** Returns an (immutable, ordered) list of comments in this BUILD file. */
  public ImmutableList<Comment> getComments() {
    return comments;
  }

  @Override
  public void prettyPrint(Appendable buffer, int indentLevel) throws IOException {
    // Only statements are printed, not comments.
    for (Statement stmt : statements) {
      stmt.prettyPrint(buffer, indentLevel);
    }
  }

  @Override
  public String toString() {
    return "<StarlarkFile with " + statements.size() + " statements>";
  }

  @Override
  public void accept(NodeVisitor visitor) {
    visitor.visit(this);
  }

  /**
   * Parse the specified file, returning its syntax tree with the preludeStatements inserted at the
   * front of its statement list.
   */
  public static StarlarkFile parseWithPrelude(
      ParserInput input, List<Statement> preludeStatements) {
    Parser.ParseResult result = Parser.parseFile(input);
    return create(
        preludeStatements, result, /* contentHashCode= */ null, /*allowImportInternal=*/ false);
  }

  /**
   * Parse the specified build file, returning its AST. All load statements parsed that way will be
   * exempt from visibility restrictions.
   */
  // TODO(adonovan): make LoadStatement.allowInternal publicly settable, and delete this.
  public static StarlarkFile parseVirtualBuildFile(
      ParserInput input, List<Statement> preludeStatements) {
    Parser.ParseResult result = Parser.parseFile(input);
    return create(
        preludeStatements, result, /* contentHashCode= */ null, /*allowImportInternal=*/ true);
  }

  // TODO(adonovan): make the digest publicly settable, and delete this.
  public static StarlarkFile parseWithDigest(ParserInput input, byte[] digest) throws IOException {
    Parser.ParseResult result = Parser.parseFile(input);
    return create(
        /* preludeStatements= */ ImmutableList.of(),
        result,
        HashCode.fromBytes(digest).toString(),
        /* allowImportInternal= */ false);
  }

  /**
   * Parse a Starlark file.
   *
   * <p>A syntax tree is always returned, even in case of error. Errors are recorded in the tree.
   * Example usage:
   *
   * <pre>
   * StarlarkFile file = StarlarkFile.parse(input);
   * if (!file.ok()) {
   *    Event.replayEventsOn(handler, file.errors());
   *    ...
   * }
   * </pre>
   */
  public static StarlarkFile parse(ParserInput input) {
    Parser.ParseResult result = Parser.parseFile(input);
    return create(
        /* preludeStatements= */ ImmutableList.of(),
        result,
        /* contentHashCode= */ null,
        /* allowImportInternal=*/ false);
  }

  // TODO(adonovan): legacy function for copybara compatibility; delete ASAP.
  public static StarlarkFile parseWithoutImports(ParserInput input, EventHandler handler) {
    StarlarkFile file = parse(input);
    Event.replayEventsOn(handler, file.errors());
    return file;
  }

  // TODO(adonovan): legacy function for copybara compatibility; delete ASAP.
  public boolean exec(StarlarkThread thread, EventHandler eventHandler)
      throws InterruptedException {
    try {
      EvalUtils.exec(this, thread);
      return true;
    } catch (EvalException ex) {
      eventHandler.handle(Event.error(ex.getLocation(), ex.getMessage()));
      return false;
    }
  }

  /**
   * Evaluates the code and return the value of the last statement if it's an Expression or else
   * null.
   */
  // TODO(adonovan): move to EvalUtils. Split into two APIs, eval(expr) and exec(file).
  // (Abolish "statement" and "file+expr" as primary API concepts.)
  // Make callers decide whether they want to execute a file or evaluate an expression.
  @Nullable
  public Object eval(StarlarkThread thread) throws EvalException, InterruptedException {
    List<Statement> stmts = statements;
    Expression expr = null;
    int n = statements.size();
    if (n > 0 && statements.get(n - 1) instanceof ExpressionStatement) {
      stmts = statements.subList(0, n - 1);
      expr = ((ExpressionStatement) statements.get(n - 1)).getExpression();
    }
    Eval.execStatements(thread, stmts);
    return expr == null ? null : Eval.eval(thread, expr);
  }

  /**
   * Parses, resolves and evaluates the input and returns the value of the last statement if it's an
   * Expression or else null. In case of scan/parse/resolver error, it throws a SyntaxError
   * containing one or more specific errors. If evaluation fails, it throws an EvalException or
   * InterruptedException. The return value is as for eval(StarlarkThread).
   */
  // Note: uses Starlark (not BUILD) validation semantics.
  // TODO(adonovan): move to EvalUtils; see other eval function.
  @Nullable
  public static Object eval(ParserInput input, StarlarkThread thread)
      throws SyntaxError, EvalException, InterruptedException {
    StarlarkFile file = parseAndValidateSkylark(input, thread);
    if (!file.ok()) {
      throw new SyntaxError(file.errors());
    }
    return file.eval(thread);
  }

  /**
   * Parses and validates the input and returns the syntax tree. It uses Starlark (not BUILD)
   * validation semantics.
   *
   * <p>The thread is primarily used for its GlobalFrame. Scan/parse/validate errors are recorded in
   * the StarlarkFile. It is the caller's responsibility to inspect them.
   */
  public static StarlarkFile parseAndValidateSkylark(ParserInput input, StarlarkThread thread) {
    StarlarkFile file = parse(input);
    ValidationEnvironment.validateFile(file, thread, /*isBuildFile=*/ false);
    return file;
  }

  /**
   * Returns a hash code calculated from the string content of the source file of this AST.
   */
  @Nullable public String getContentHashCode() {
    return contentHashCode;
  }
}
