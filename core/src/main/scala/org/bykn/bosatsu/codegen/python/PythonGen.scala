package org.bykn.bosatsu.codegen.python

import cats.Monad
import cats.data.{NonEmptyList, State}
import cats.parse.{Parser => P}
import org.bykn.bosatsu.{PackageName, Identifier, Matchless, Par, Parser, RecursionKind}
import org.bykn.bosatsu.rankn.Type
import org.typelevel.paiges.Doc

import Identifier.Bindable
import Matchless._

import cats.implicits._

object PythonGen {
  import Code.{ValueLike, Statement, Expression}

  type Module = NonEmptyList[Code.Ident]

  sealed abstract class Env[+A]
  object Env {
    import Code._

    def pure[A](a: A): Env[A] = envMonad.pure(a)

    implicit def envMonad: Monad[Env] =
      new Monad[Env] {
        import Impl._

        val m = Monad[State[EnvState, *]]
        def pure[A](a: A): Env[A] = EnvImpl(m.pure(a))
        override def map[A, B](ea: Env[A])(fn: A => B): Env[B] =
          EnvImpl(m.map(ea.state)(fn))
        def flatMap[A, B](ea: Env[A])(fn: A => Env[B]): Env[B] =
          EnvImpl(m.flatMap(ea.state)(fn.andThen(_.state)))
        def tailRecM[A, B](a: A)(fn: A => Env[Either[A, B]]): Env[B] =
          EnvImpl(m.tailRecM(a)(fn.andThen(_.state)))
      }

    private object Impl {

      case class EnvState(
        imports: Map[Module, Code.Ident],
        bindings: Map[Bindable, (Int, List[Code.Ident])],
        tops: Set[Bindable],
        nextTmp: Long) {

        private def bindInc(b: Bindable, inc: Int)(fn: Int => Code.Ident): (EnvState, Code.Ident) = {
          val (c, s) = bindings.getOrElse(b, (0, Nil))
          val pname = fn(c)

          (copy(
            bindings = bindings.updated(b, (c + inc, pname :: s))
          ), pname)
        }

        def bind(b: Bindable): (EnvState, Code.Ident) =
          bindInc(b, 1) { c =>
            Code.Ident(escapeRaw("___b", b.asString + c.toString))
          }

        // in loops we need to substitute
        // bindings for mutable variables
        def subs(b: Bindable, c: Code.Ident): EnvState =
          bindInc(b, 0)(_ => c)._1

        def deref(b: Bindable): Code.Ident =
          // see if we are shadowing, or top level
          bindings.get(b) match {
            case Some((_, h :: _)) => h
            case _ if tops(b) => escape(b)
            case other =>
              // $COVERAGE-OFF$
              throw new IllegalStateException(s"unexpected deref: $b with bindings: $other")
              // $COVERAGE-ON$
          }

        def unbind(b: Bindable): EnvState =
          bindings.get(b) match {
            case Some((cnt, _ :: tail)) =>
              copy(bindings = bindings.updated(b, (cnt, tail)))
            case other =>
              // $COVERAGE-OFF$
              throw new IllegalStateException(s"invalid scope: $other for $b with $bindings")
              // $COVERAGE-ON$
          }

        def getNextTmp: (EnvState, Long) = (copy(nextTmp = nextTmp + 1L), nextTmp)

        def topLevel(b: Bindable): (EnvState, Code.Ident) =
          (copy(tops = tops + b), escape(b))

        def addImport(mod: Module): (EnvState, Code.Ident) =
          imports.get(mod) match {
            case Some(alias) => (this, alias)
            case None =>
              val impNumber = imports.size
              val alias = Code.Ident(escapeRaw("___i", mod.last.name + impNumber.toString))
              (copy(imports = imports.updated(mod, alias)), alias)
          }

        def importStatements: List[Code.Import] =
          imports
            .iterator
            .map { case (path, alias) =>
              val modName = path.map(_.name).toList.mkString(".")
              Code.Import(modName, Some(alias))
            }
            .toList
            .sortBy(_.modname)
      }

      implicit class EnvOps[A](val env: Env[A]) extends AnyVal {
        def state: State[EnvState, A] =
          env.asInstanceOf[EnvImpl[A]].state
      }

      def emptyState: EnvState =
        EnvState(Map.empty, Map.empty, Set.empty, 0L)

      case class EnvImpl[A](state: State[EnvState, A]) extends Env[A]

      def env[A](fn: EnvState => (EnvState, A)): Env[A] =
        EnvImpl(State(fn))

      def read[A](fn: EnvState => A): Env[A] =
        EnvImpl(State { state => (state, fn(state)) })

      def update(fn: EnvState => EnvState): Env[Unit] =
        EnvImpl(State { state => (fn(state), ()) })

      def run[A](env: Env[A]): (EnvState, A) =
        env.state.run(emptyState).value
    }

    def render(env: Env[List[Statement]]): Doc = {
      val (state, stmts) = Impl.run(env)

      val imps = state.importStatements

      val impDocs = Doc.intercalate(Doc.hardLine, imps.map(Code.toDoc))
      val twoLines = Doc.hardLine + Doc.hardLine
      Doc.intercalate(twoLines, impDocs :: stmts.map(Code.toDoc))
    }

    // allocate a unique identifier for b
    def bind(b: Bindable): Env[Code.Ident] =
      Impl.env(_.bind(b))

    // point this name to the top level name
    def subs(b: Bindable, i: Code.Ident): Env[Unit] =
      Impl.update(_.subs(b, i))

    // get the mapping for a name in scope
    def deref(b: Bindable): Env[Code.Ident] =
      Impl.read(_.deref(b))

    // release the current scope for b
    def unbind(b: Bindable): Env[Unit] =
      Impl.update(_.unbind(b))

    def nameForAnon(long: Long): Env[Code.Ident] =
      Env.pure(Code.Ident(s"___a$long"))

    def newAssignableVar: Env[Code.Ident] =
      Impl.env(_.getNextTmp)
        .map { long =>
          Code.Ident(s"___t$long")
        }

    def importPackage(pack: PackageName): Env[Code.Ident] =
      importEscape(pack.parts)

    def importEscape(parts: NonEmptyList[String]): Env[Code.Ident] =
      importLiteral(parts.map(escapeModule))

    def importLiteral(parts: NonEmptyList[Code.Ident]): Env[Code.Ident] =
      Impl.env(_.addImport(parts))

    // top level names are imported across files so they have
    // to be consistently transformed
    def topLevelName(n: Bindable): Env[Code.Ident] =
      Impl.env(_.topLevel(n))

    def onLastsM(cs: List[ValueLike])(fn: List[Expression] => Env[ValueLike]): Env[ValueLike] = {
      def loop(cs: List[ValueLike], setup: List[Statement], args: List[Expression]): Env[ValueLike] =
        cs match {
          case Nil =>
            val res = fn(args.reverse)
            NonEmptyList.fromList(setup) match {
              case None => res
              case Some(nel) =>
                val stmts = nel.reverse
                val stmt = Code.block(stmts.head, stmts.tail :_*)
                res.map(stmt.withValue(_))
            }
          case (e: Expression) :: t => loop(t, setup, e :: args)
          case (ifelse@IfElse(_, _)) :: tail =>
            // we allocate a result and assign
            // the result on each value
            Env.newAssignableVar.flatMap { v =>
              loop(tail, (v := ifelse) :: setup, v :: args)
            }
          case WithValue(decl, v) :: tail =>
            loop(v :: tail, decl :: setup, args)
        }

      loop(cs, Nil, Nil)
    }

    def onLasts(cs: List[ValueLike])(fn: List[Expression] => ValueLike): Env[ValueLike] =
      onLastsM(cs)(fn.andThen(Env.pure(_)))

    def onLastM(c: ValueLike)(fn: Expression => Env[ValueLike]): Env[ValueLike] =
      onLastsM(c :: Nil) {
        case x :: Nil => fn(x)
        case other =>
          // $COVERAGE-OFF$
          throw new IllegalStateException(s"expected list to have size 1: $other")
          // $COVERAGE-ON$
      }

    def onLast(c: ValueLike)(fn: Expression => ValueLike): Env[ValueLike] =
      onLastM(c)(fn.andThen(Env.pure(_)))

    def onLast2(c1: ValueLike, c2: ValueLike)(fn: (Expression, Expression) => ValueLike): Env[ValueLike] =
      onLasts(c1 :: c2 :: Nil) {
        case x1 :: x2 :: Nil => fn(x1, x2)
        case other =>
          // $COVERAGE-OFF$
          throw new IllegalStateException(s"expected list to have size 2: $other")
          // $COVERAGE-ON$
      }

    def ifElse(conds: NonEmptyList[(ValueLike, ValueLike)], elseV: ValueLike): Env[ValueLike] = {
      // for all the non-expression conditions, we need to defer evaluating them
      // until they are really needed
      conds match {
        case NonEmptyList((cx: Expression, t), Nil) =>
          (t, elseV) match {
            case (tx: Expression, elseX: Expression) =>
              Env.pure(Ternary(tx, cx, elseX).simplify)
            case _ =>
              Env.pure(IfElse(NonEmptyList.one((cx, t)), elseV))
          }
        case NonEmptyList((cx: Expression, t), rh :: rt) =>
          val head = (cx, t)
          ifElse(NonEmptyList(rh, rt), elseV).map {
            case IfElse(crest, er) =>
              // preserve IfElse chains
              IfElse(head :: crest, er)
            case nestX: Expression =>
              t match {
                case tx: Expression =>
                  Ternary(tx, cx, nestX).simplify
                case _ =>
                  IfElse(NonEmptyList.one(head), nestX)
              }
            case nest =>
              IfElse(NonEmptyList.one(head), nest)
          }
        case NonEmptyList((cx, t), rest) =>
          for {
            // allocate a new unshadowable var
            cv <- Env.newAssignableVar
            res <- ifElse(NonEmptyList((cv, t), rest), elseV)
          } yield (cv := cx).withValue(res)
      }
    }

    def ifElseS(cond: ValueLike, thenS: Statement, elseS: Statement): Env[Statement] =
      cond match {
        case x: Expression => Env.pure(Code.ifElseS(x, thenS, elseS))
        case WithValue(stmt, vl) =>
          ifElseS(vl, thenS, elseS).map(stmt +: _)
        case v =>
          // this is a branch, don't multiply code by writing on each
          // branch, that could give an exponential blowup
          Env.newAssignableVar
            .map { tmp =>
              Code.block(
                tmp := v,
                Code.ifElseS(tmp, thenS, elseS)
              )
            }
      }

    def andCode(c1: ValueLike, c2: ValueLike): Env[ValueLike] =
      (c1, c2) match {
        case (t: Expression, c2) if t.simplify == Code.Const.True => Env.pure(c2)
        case (_, x2: Expression) =>
          onLast(c1)(_.evalAnd(x2))
        case _ =>
          // we know that c2 is not a simple expression
          // res = False
          // if c1:
          //   res = c2
          Env.onLastM(c1) { x1 =>
            for {
              res <- Env.newAssignableVar
              ifstmt <- ifElseS(x1, res := c2, Code.Pass)
            } yield {
                Code.block(
                  res := Code.Const.False,
                  ifstmt
                )
                .withValue(res)
            }
          }
      }

    def makeDef(defName: Code.Ident, arg: NonEmptyList[Code.Ident], v: ValueLike): Code.Def =
      Code.Def(defName, arg.toList, toReturn(v))

    def replaceTailCallWithAssign(name: Ident, argSize: Int, body: ValueLike)(onArgs: List[Expression] => Statement): Env[ValueLike] = {
      val initBody = body
      def loop(body: ValueLike): Env[ValueLike] =
        body match {
          case a@Apply(fn0, args0) =>
            if (fn0 == name) {
              if (args0.length == argSize) {
                val all = onArgs(args0)
                // set all the values and return the empty tuple
                Env.pure(all.withValue(Code.Const.Unit))
              }
              else {
                // $COVERAGE-OFF$
                throw new IllegalStateException(s"expected a tailcall for $name in $initBody, but found: $a")
                // $COVERAGE-ON$
              }
            }
            else {
              Env.pure(a)
            }
          case Parens(p) => loop(p).flatMap(onLast(_)(Parens(_)))
          case IfElse(ifCases, elseCase) =>
            // only the result types are in tail position, we don't need to recurse on conds
            val ifs = ifCases.traverse { case (cond, res) => loop(res).map((cond, _)) }
            (ifs, loop(elseCase))
              .mapN(ifElse(_, _))
              .flatten
          case Ternary(ifTrue, cond, ifFalse) =>
            // both results are in the tail position
            (loop(ifTrue), loop(ifFalse))
              .mapN { (t, f) =>
                ifElse(NonEmptyList.one((cond, t)), f)
              }
              .flatten
          case WithValue(stmt, v) =>
            loop(v).map(stmt.withValue(_))
          // the rest cannot have a call in the tail position
          case DotSelect(_, _) | Op(_, _, _) | Lambda(_, _) | MakeTuple(_) | MakeList(_) | SelectItem(_, _) | SelectRange(_, _, _) | Ident(_) | PyBool(_) | PyString(_) | PyInt(_) => Env.pure(body)
        }

      loop(initBody)
    }

    // these are always recursive so we can use def to define them
    def buildLoop(selfName: Ident, fnMutArgs: NonEmptyList[(Ident, Ident)], body: ValueLike): Env[Statement] = {
      /*
       * bodyUpdate = body except App(foo, args) is replaced with
       * reseting the inputs, and setting cont to True and having
       * the value ()
       *
       * def foo(a)(b)(c):
       *   cont = True
       *   res = ()
       *   while cont:
       *     cont = False
       *     res = bodyUpdate
       *   return res
       */
      val fnArgs = fnMutArgs.map(_._1)
      val mutArgs = fnMutArgs.map(_._2)

      def assignMut(cont: Code.Ident)(args: List[Expression]): Statement = {
        // do the replacement in one atomic go. otherwise
        // we could mutate a variable a later expression depends on
        // some times we generate code that does x = x, remove those cases
        val (left, right) =
          mutArgs.toList.zip(args)
            .filter { case (x, y) => x != y }
            .unzip

        Code.block(
          cont := Const.True,
          if (left.isEmpty) Pass
          else if (left.lengthCompare(1) == 0) {
            left.head := right.head
          }
          else {
            (MakeTuple(left) := MakeTuple(right))
          }
        )
      }

      for {
        cont <- Env.newAssignableVar
        ac = assignMut(cont)(fnArgs.toList)
        res <- Env.newAssignableVar
        ar = (res := Code.Const.Unit)
        body1 <- replaceTailCallWithAssign(selfName, mutArgs.length, body)(assignMut(cont))
        setRes = res := body1
        loop = While(cont, (cont := false) +: setRes)
        newBody = (ac +: ar +: loop).withValue(res)
      } yield makeDef(selfName, fnArgs, newBody)
    }

  }

  private[this] val base62Items = (('0' to '9') ++ ('A' to 'Z') ++ ('a' to 'z')).toSet

  private def toBase62(c: Char): String =

    if (base62Items(c)) c.toString
    else if (c == '_') "__"
    else {
      def toChar(i0: Int): Char =
        if (i0 < 0) {
          // $COVERAGE-OFF$
          sys.error(s"invalid in: $i0")
          // $COVERAGE-ON$
        }
        else if (i0 < 10) (i0 + '0'.toInt).toChar
        else if (i0 < 36) (i0 - 10 + 'A'.toInt).toChar
        else if (i0 < 62) (i0 - 36 + 'a'.toInt).toChar
        else {
          // $COVERAGE-OFF$
          sys.error(s"invalid int: $i0")
          // $COVERAGE-ON$
        }

      def toString(i: Int): String = {
        if (i < 62) toChar(i).toString
        else {
          val i0 = i % 62
          val i1 = i / 62
          toString(i1) + toChar(i0)
        }
      }

      "_" + toString(c.toInt) + "_"
    }

  private def escapeRaw(prefix: String, str: String): String =
    str.map(toBase62).mkString(prefix, "", "")

  private def unBase62(str: String, offset: Int, bldr: java.lang.StringBuilder): Int = {
    var idx = offset
    var num = 0

    while(idx < str.length) {
      val c = str.charAt(idx)
      idx += 1
      if (c == '_') {
        if (idx != offset + 1) {
          // done
          val numC = num.toChar
          bldr.append(numC)
          return (idx - offset)
        }
        else {
          // "__" decodes to "_"
          bldr.append('_')
          return (idx - offset)
        }
      }
      else {
        val base =
          if (c <= '9') '0'.toInt
          else if (c <= 'Z') ('A'.toInt - 10)
          else ('a'.toInt - 36)

        num = num * 62 + c.toInt - base
      }
    }
    return -1
  }

  // we escape by prefixing by three underscores, ___ and n (for name)
  // we use other ___x escapes for different name spaces, e.g. tmps, and anons
  // then we escape _ by __ and any character outside the allowed
  // range by _base 62_
  // ___t: tmp
  // ___a anons
  // ___n: name
  // ___m: modules
  // ___i: import alias
  // ___b: shadowable (internal) names
  def escape(n: Bindable): Code.Ident = {
    val str = n.asString
    if (!str.startsWith("___") && Code.python2Name.matcher(str).matches && !Code.pyKeywordList(str)) Code.Ident(str)
    else {
      // we need to escape
      Code.Ident(escapeRaw("___n", str))
    }
  }

  def escapeModule(str: String): Code.Ident = {
    if (!str.startsWith("___") && Code.python2Name.matcher(str).matches && !Code.pyKeywordList(str)) Code.Ident(str)
    else {
      // we need to escape
      Code.Ident(escapeRaw("___m", str))
    }
  }

  def unescape(ident: Code.Ident): Option[Bindable] = {
    val str = ident.name
    val res = if (str.startsWith("___n")) {
      val bldr = new java.lang.StringBuilder()
      var idx = 4
      while (idx < str.length) {
        val c = str.charAt(idx)
        idx += 1
        if (c == '_') {
          val res = unBase62(str, idx, bldr)
          if (res < 1) return None
          else {
            idx += res
          }
        }
        else {
          bldr.append(c)
        }
      }

      bldr.toString()
    }
    else {
      str
    }

    if (str.isEmpty) None
    else {
      Identifier
        .optionParse(Identifier.bindableParser, res)
        .orElse(Some(Identifier.Backticked(res)))
    }
  }

  /**
   * Remap is used to handle remapping external values
   */
  private def apply(packName: PackageName, name: Bindable, me: Expr)(remap: (PackageName, Bindable) => Env[Option[ValueLike]]): Env[Statement] = {
    val ops = new Impl.Ops(packName, remap)

    // if we have a top level let rec with the same name, handle it more cleanly
    me match {
      case Let(Right((n1, RecursionKind.NonRecursive)), inner, Local(n2)) if ((n1 === name) && (n2 === name)) =>
        // we can just bind now at the top level
        for {
          nm <- Env.topLevelName(name)
          res <- inner match {
            case fn: FnExpr => ops.topFn(nm, fn, None)
            case _ => ops.loop(inner, None).map(nm := _)
          }
        } yield res
      case Let(Right((n1, RecursionKind.Recursive)), fn: FnExpr, Local(n2))
        if (n1 === name) && (n2 === name) =>
        for {
          nm <- Env.topLevelName(name)
          res <- ops.topFn(nm, fn, None)
        } yield res
      case fn: FnExpr =>
        for {
          nm <- Env.topLevelName(name)
          res <- ops.topFn(nm, fn, None)
        } yield res
      case _ =>
        for {
          // name is not in scope yet
          ve <- ops.loop(me, None)
          nm <- Env.topLevelName(name)
        } yield nm := ve
    }
  }

  private def addUnitTest(name: Bindable): Env[Statement] = {
    // we could inspect the Expr, but for now, we will just put
    // everything in a single test:
    // class BosatsuTests(unittest.TestCase):
    //   def test_all(self):
    //     # iterate through making assertions
    //
    (Env.importLiteral(NonEmptyList.one(Code.Ident("unittest"))),
      Env.newAssignableVar,
      Env.topLevelName(name)
      )
      .mapN { (importedName, tmpVar, testName) =>
        import Impl._

        val loopName = Code.Ident("test_loop")
        val argName = Code.Ident("value")
        val selfName = Code.Ident("self")

        val isAssertion: Code.Expression =
          argName.get(0) =:= 0

        // Assertion(bool, msg)
        val testAssertion: Code.Statement =
          Code.Call(Code.Apply(selfName.dot(Code.Ident("assertTrue")),
            argName.get(1) :: argName.get(2) :: Nil))

        // TestSuite(suiteName, tests)
        val testSuite: Code.Statement =
          Code.block(
            tmpVar := argName.get(2), // get the test list
            Code.While(isNonEmpty(tmpVar),
              Code.block(
                Code.Call(Code.Apply(loopName, headList(tmpVar) :: Nil)),
                tmpVar := tailList(tmpVar)
              )
            )
        )

        val loopBody: Code.Statement =
          Code.IfStatement(
            NonEmptyList.one((isAssertion, testAssertion)),
            Some(testSuite))

        val recTest =
          Code.Def(
            loopName,
            argName :: Nil,
            loopBody)

        val body =
          Code.block(
            recTest,
            Code.Call(Code.Apply(loopName, testName :: Nil)))

        val defBody =
          Code.Def(Code.Ident("test_all"),
            selfName :: Nil,
            body)

        Code.ClassDef(Code.Ident("BosatsuTests"), List(importedName.dot(Code.Ident("TestCase"))),
          defBody)
      }
  }

  private def addMainEval(name: Bindable, mod: Module, ci: Code.Ident): Env[Statement] =
    /*
     * this does:
     * if __name__ == "__main__":
     *   from Module import ci
     *   ci(name)
     */
    (Env.importLiteral(mod), Env.topLevelName(name))
      .mapN { (importedName, argName) =>
        Code.mainStatement(
          Code.Call(importedName.dot(ci)(argName))
        )
      }

  private val modParser: P[(Module, Code.Ident)] = {
    val identParser: P[Code.Ident] = Parser.py2Ident.map(Code.Ident(_))
    P.repSep(identParser, min = 2, sep = P.char('.'))
      .map { items =>
        // min = 2 ensures this is safe
        (NonEmptyList.fromListUnsafe(items.init.toList), items.last)
      }
  }

  // parses a nested map with
  //
  // { packageName: { bind: foo.bar.baz } }
  //
  val externalParser: P[List[(PackageName, Bindable, Module, Code.Ident)]] = {

    val inner: P[List[(Bindable, (Module, Code.Ident))]] =
      Parser.dictLikeParser(Identifier.bindableParser, modParser)

    val outer: P[List[(PackageName, List[(Bindable, (Module, Code.Ident))])]] =
      Parser.maybeSpacesAndLines.with1 *> Parser.dictLikeParser(PackageName.parser, inner) <* Parser.maybeSpacesAndLines

    outer.map { items =>
      items.flatMap { case (p, bs) =>
        bs.map { case (b, (m, i)) => (p, b, m, i) }
      }
    }
  }

  // parses a map of of evaluators
  // { fullyqualifiedType: foo.bar.baz, }
  val evaluatorParser: P[List[(Type, (Module, Code.Ident))]] =
    Parser.maybeSpacesAndLines.with1 *> Parser.dictLikeParser(Type.fullyResolvedParser, modParser) <* Parser.maybeSpacesAndLines

  // compile a set of packages given a set of external remappings
  def renderAll(
    pm: Map[PackageName, List[(Bindable, Expr)]],
    externals: Map[(PackageName, Bindable), (Module, Code.Ident)],
    tests: Map[PackageName, Bindable],
    evaluators: Map[PackageName, (Bindable, Module, Code.Ident)])(implicit ec: Par.EC): Map[PackageName, (Module, Doc)] = {

    val externalRemap: (PackageName, Bindable) => Env[Option[ValueLike]] =
      { (p, b) =>
        externals.get((p, b)) match {
          case None => Env.pure(None)
          case Some((m, i)) =>
            Env.importLiteral(m)
              .map { alias => Some(Code.DotSelect(alias, i)) }
        }
      }

    val all = pm
      .toList
      .traverse { case (p, lets) =>
        Par.start {
          val stmts0: Env[List[Statement]] =
            lets
              .traverse { case (b, x) =>
                apply(p, b, x)(externalRemap)
              }

          val evalStmt: Env[Option[Statement]] =
            evaluators.get(p).traverse { case (b, m, c) => addMainEval(b, m, c) }

          val testStmt: Env[Option[Statement]] =
            tests.get(p).traverse(addUnitTest)

          val stmts = (stmts0, testStmt, evalStmt)
            .mapN { (s, optT, optM) =>
              s :++ optT.toList :++ optM.toList
            }

          def modName(p: NonEmptyList[String]): Module =
            p match {
              case NonEmptyList(h, Nil) =>
                val Code.Ident(m) = escapeModule(h)

                NonEmptyList.one(Code.Ident(m + ".py"))
              case NonEmptyList(h, t1 :: t2) =>
                escapeModule(h) :: modName(NonEmptyList(t1, t2))
            }

          (p, (modName(p.parts), Env.render(stmts)))
        }
      }

    Par.await(all).toMap
  }

  // These are values replaced with python operations
  def intrinsicValues: Map[PackageName, Set[Bindable]] =
    Map((PackageName.PredefName, Impl.PredefExternal.results.keySet))

  private object Impl {

    val emptyList: Expression =
      Code.MakeTuple(Code.fromInt(0) :: Nil)

    def consList(head: Expression, tail: Expression): Expression =
      Code.MakeTuple(List(Code.fromInt(1), head, tail))

    def isNonEmpty(expr: Expression): Expression =
      Code.Op(expr.get(0), Code.Const.Neq, Code.fromInt(0)).simplify

    def headList(lst: Expression): Expression =
      lst.get(1).simplify

    def tailList(lst: Expression): Expression =
      lst.get(2).simplify

    object PredefExternal {
      private val cmpFn: List[ValueLike] => Env[ValueLike] = {
        input =>
          Env.onLast2(input.head, input.tail.head) { (arg0, arg1) =>
              Code.Ternary(
                0,
                arg0 :< arg1,
                Code.Ternary(1, arg0 =:= arg1, 2)
              ).simplify
          }
      }

      val results: Map[Bindable, (List[ValueLike] => Env[ValueLike], Int)] =
        Map(
          (Identifier.unsafeBindable("add"),
            (
              input => Env.onLast2(input.head, input.tail.head)(_.evalPlus(_))
            , 2)),
          (Identifier.unsafeBindable("sub"),
            ({
              input => Env.onLast2(input.head, input.tail.head)(_.evalMinus(_))
            } , 2)),
          (Identifier.unsafeBindable("times"),
            ({
              input => Env.onLast2(input.head, input.tail.head)(_.evalTimes(_))
            }, 2)),
          (Identifier.unsafeBindable("div"),
            ({
              input => Env.onLast2(input.head, input.tail.head) { (a, b) =>
                Code.Ternary(
                  Code.Op(a, Code.Const.Div, b),
                  b, // 0 is false in python
                  0
                ).simplify
              }
            }, 2)),
          (Identifier.unsafeBindable("mod_Int"),
            ({
              input => Env.onLast2(input.head, input.tail.head) { (a, b) =>
                Code.Ternary(
                  Code.Op(a, Code.Const.Mod, b),
                  b, // 0 is false in python
                  a
                ).simplify
              }
            }, 2)),
          (Identifier.unsafeBindable("cmp_Int"), (cmpFn, 2)),
          (Identifier.unsafeBindable("eq_Int"),
            ({
              input => Env.onLast2(input.head, input.tail.head)(_.eval(Code.Const.Eq, _))
            }, 2)),

          (Identifier.unsafeBindable("shift_left_Int"),
            ({
              input => Env.onLast2(input.head, input.tail.head)(_.eval(Code.Const.BitwiseShiftLeft, _))
            }, 2)),
          (Identifier.unsafeBindable("shift_right_Int"),
            ({
              input => Env.onLast2(input.head, input.tail.head)(_.eval(Code.Const.BitwiseShiftRight, _))
            }, 2)),
          (Identifier.unsafeBindable("and_Int"),
            ({
              input => Env.onLast2(input.head, input.tail.head)(_.eval(Code.Const.BitwiseAnd, _))
            }, 2)),
          (Identifier.unsafeBindable("or_Int"),
            ({
              input => Env.onLast2(input.head, input.tail.head)(_.eval(Code.Const.BitwiseOr, _))
            }, 2)),
          (Identifier.unsafeBindable("xor_Int"),
            ({
              input => Env.onLast2(input.head, input.tail.head)(_.eval(Code.Const.BitwiseXor, _))
            }, 2)),
          (Identifier.unsafeBindable("not_Int"),
            ({
              // leverage not(x) == -1 - x 
              input => Env.onLast(input.head)(Code.fromInt(-1).evalMinus(_))
            }, 2)),
          (Identifier.unsafeBindable("gcd_Int"),
            ({
              input =>
                (Env.newAssignableVar, Env.newAssignableVar, Env.newAssignableVar)
                  .mapN { (tmpa, tmpb, tmpc) =>
                    Env.onLast2(input.head, input.tail.head) { (a, b) =>
                      Code.block(
                        tmpa := a,
                        tmpb := b,
                        Code.While(tmpb,
                          Code.block(
                            tmpc := tmpb,
                            // we know b != 0 because we are in the while loop
                            /// b = a % b
                            tmpb := Code.Op(tmpa, Code.Const.Mod, tmpb),
                            tmpa := tmpc
                          )
                        )
                      )
                      .withValue(tmpa)
                    }
                  }
                  .flatten
            }, 2)),
            //external def int_loop(intValue: Int, state: a, fn: (Int, a) -> (Int, a) -> a
            // def int_loop(i, a, fn):
            //   if i <= 0: a
            //   else:
            //     (i1, a1) = fn(i, a)
            //     if i <= i1: a
            //     else int_loop(i1, a, fn)
            //
            // def int_loop(i, a, fn):
            //   cont = (0 < i)
            //   res = a
            //   _i = i
            //   _a = a
            //   while cont:
            //     res = fn(_i, _a)
            //     tmp_i = res[0]
            //     _a = res[1][0]
            //     cont = (0 < tmp_i) and (tmp_i < _i)
            //     _i = tmp_i
            //   return _a
          (Identifier.unsafeBindable("int_loop"),
            ({
              input =>
                (Env.newAssignableVar, Env.newAssignableVar, Env.newAssignableVar, Env.newAssignableVar, Env.newAssignableVar)
                  .tupled
                  .flatMap { case (cont, res, _i, _a, tmp_i) =>
                    Env.onLasts(input) {
                      case i :: a :: fn :: Nil =>
                        Code.block(
                          cont := (Code.fromInt(0) :< i),
                          res := a,
                          _i := i,
                          _a := a,
                          Code.While(cont, {
                            fn(_i, _a).simplify match {
                              case Code.MakeTuple(fst :: snd :: Nil) =>
                                // inline the tuple allocation and destructuring 
                                Code.block(
                                  tmp_i := fst,
                                  _a := snd,
                                  cont := (Code.fromInt(0) :< tmp_i).evalAnd(tmp_i :< _i),
                                  _i := tmp_i
                                )
                              case notTup =>
                                Code.block(
                                  res := notTup,
                                  tmp_i := res.get(0),
                                  _a := res.get(1),
                                  cont := (Code.fromInt(0) :< tmp_i).evalAnd(tmp_i :< _i),
                                  _i := tmp_i
                                )
                            }
                          }
                          )
                        )
                        .withValue(_a)
                      case other =>
                        // $COVERAGE-OFF$
                        throw new IllegalStateException(s"expected arity 3 got: $other")
                        // $COVERAGE-ON$
                    }
                  }
            }, 3)),
        (Identifier.unsafeBindable("concat_String"),
          ({ input =>
            Env.onLastM(input.head) { listOfStrings =>
              // convert to python list, then call "".join(seq)
              Env.newAssignableVar
                .flatMap { pyList =>
                  bosatsuListToPython(pyList, listOfStrings)
                    .map { loop =>
                      Code.block(
                        pyList := Code.MakeList(Nil),
                        loop
                      )
                      .withValue {
                        Code.PyString("").dot(Code.Ident("join"))(pyList)
                      }
                    }
                }
            }
          }, 1)),
        (Identifier.unsafeBindable("int_to_String"),
          ({
            input => Env.onLast(input.head) {
              case Code.PyInt(i) => Code.PyString(i.toString)
              case i => Code.Apply(Code.DotSelect(i, Code.Ident("__str__")), Nil)
            }
          }, 1)),
        (Identifier.unsafeBindable("char_to_String"),
          // we encode chars as strings so this is just identity
          ({ input => Env.envMonad.pure(input.head) }, 1)),
        (Identifier.unsafeBindable("trace"),
          ({
            input => Env.onLast2(input.head, input.tail.head) { (msg, i) =>
              Code.Call(Code.Apply(Code.Ident("print"), msg :: i :: Nil))
                .withValue(i)
            }
          }, 2)),
        (Identifier.unsafeBindable("partition_String"),
          ({
            input =>
              Env.newAssignableVar
                .flatMap { res =>
                  Env.onLast2(input.head, input.tail.head) { (str, sep) =>
                  // if sep == "": None
                  // else:
                  //   (a, s1, b) = str.partition(sep)
                  //   if s1: (1, (a, b))
                  //   else: (0, )
                  val a = res.get(0)
                  val s1 = res.get(1)
                  val b = res.get(2)
                  val success = Code.MakeTuple(Code.fromInt(1) ::
                    Code.MakeTuple(a :: b :: Nil) ::
                    Nil
                    )
                  val fail = Code.MakeTuple(Code.fromInt(0) :: Nil)
                  val nonEmpty =
                    (res := str.dot(Code.Ident("partition"))(sep))
                      .withValue(Code.Ternary(success, s1, fail))

                  Code.IfElse(NonEmptyList.one((sep, nonEmpty)), fail)
                }
              }
          }, 2)),
        (Identifier.unsafeBindable("rpartition_String"),
          ({
            input =>
              Env.newAssignableVar
                .flatMap { res =>
                  Env.onLast2(input.head, input.tail.head) { (str, sep) =>
                  // (a, s1, b) = str.partition(sep)
                  // if s1: (1, (a, b))
                  // else: (0, )
                  val a = res.get(0)
                  val s1 = res.get(1)
                  val b = res.get(2)
                  val success = Code.MakeTuple(Code.fromInt(1) ::
                    Code.MakeTuple(a :: b :: Nil) ::
                    Nil
                    )
                  val fail = Code.MakeTuple(Code.fromInt(0) :: Nil)
                  val nonEmpty =
                    (res := str.dot(Code.Ident("rpartition"))(sep))
                      .withValue(Code.Ternary(success, s1, fail))

                  Code.IfElse(NonEmptyList.one((sep, nonEmpty)), fail)
                }
              }
          }, 2)),
        (Identifier.unsafeBindable("string_Order_fn"), (cmpFn, 2))
      )

      def bosatsuListToPython(pyList: Code.Ident, bList: Expression): Env[Statement] =
        Env.newAssignableVar
          .map { tmp =>
            // tmp = bList
            // while tmp != 0:
            //   //(_, h, tail) = tmp
            //   pyList.append(tmp[1])
            //   tmp = tmp[2]
            Code.block(
              tmp := bList,
              Code.While(isNonEmpty(tmp),
                Code.block(
                  Code.Call(pyList.dot(Code.Ident("append"))(headList(tmp))),
                  tmp := tailList(tmp)
                )
              )
            )
          }

      def unapply(expr: Expr): Option[(List[ValueLike] => Env[ValueLike], Int)] =
        expr match {
          case Global(PackageName.PredefName, name) => results.get(name)
          case _ => None
        }

      def makeLambda(arity: Int)(fn: List[ValueLike] => Env[ValueLike]): Env[ValueLike] =
        for {
          vars <- (1 to arity).toList.traverse(_ => Env.newAssignableVar)
          body <- fn(vars)
          // TODO: if body isn't an expression, how can just adding a lambda
          // at the end be correct? the arguments will be below points that used it.
          // the onLast has to handle Code.Lambda specially
          res <- Env.onLast(body)(Code.Lambda(vars, _))
        } yield res
    }

    class Ops(packName: PackageName, remap: (PackageName, Bindable) => Env[Option[ValueLike]]) {
      /*
       * enums with no fields are integers
       * enums and structs are tuples
       * enums first parameter is their index
       * nats are just integers
       */
      def makeCons(ce: ConsExpr, args: List[ValueLike]): Env[ValueLike] = {
        // invariant: args.size == arity
        def applyAll(args: List[ValueLike]): Env[ValueLike] =
          ce match {
            case MakeEnum(variant, _, famArities) =>
              // if all arities are 0, we use
              // integers to represent,
              // otherwise, we use tuples with the first
              // item being the variant
              val useInts = famArities.forall(_ == 0)
              val vExpr = Code.fromInt(variant)
              if (useInts) Env.pure(vExpr)
              else {
                // we make a tuple with the variant in the first position
                Env.onLasts(vExpr :: args)(Code.MakeTuple(_))
              }
            case MakeStruct(arity) =>
                if (arity == 0) Env.pure(Code.Const.Unit)
                else if (arity == 1) Env.pure(args.head)
                else Env.onLasts(args)(Code.MakeTuple(_))
            case ZeroNat =>
              Env.pure(Code.Const.Zero)
            case SuccNat =>
              Env.onLast(args.head)(_.evalPlus(Code.Const.One))
          }

        val sz = args.size
        def makeLam(cnt: Int, args: List[ValueLike]): Env[ValueLike] =
          if (cnt == 0) applyAll(args)
          else if (cnt < 0) {
            // too many args, this shouldn't typecheck
            // $COVERAGE-OFF$
            throw new IllegalStateException(s"invalid arity $sz for $ce")
            // $COVERAGE-ON$
          }
          else {
            // this is the case where we are using the constructor like a function
            assert(args.isEmpty)
            for {
              vs <- (1 to cnt).toList.traverse(_ => Env.newAssignableVar)
              body <- applyAll(vs)
              // TODO: if body isn't an expression, how can just adding a lambda
              // at the end be correct? the arguments will be below points that used it.
              // the onLast has to handle Code.Lambda specially
              res <- Env.onLast(body)(Code.Lambda(vs, _))
            } yield res
          }

        makeLam(ce.arity - sz, args)
      }

      def boolExpr(ix: BoolExpr, slotName: Option[Code.Ident]): Env[ValueLike] =
        ix match {
          case EqualsLit(expr, lit) =>
            val literal = Code.litToExpr(lit)
            loop(expr, slotName).flatMap(Env.onLast(_) { ex => ex =:= literal })
          case EqualsNat(nat, zeroOrSucc) =>
            val natF = loop(nat, slotName)

            if (zeroOrSucc.isZero)
              natF.flatMap(Env.onLast(_)(_ =:= 0))
            else
              natF.flatMap(Env.onLast(_)(_ :> 0))

          case TrueConst => Env.pure(Code.Const.True)
          case And(ix1, ix2) =>
            (boolExpr(ix1, slotName), boolExpr(ix2, slotName))
              .mapN(Env.andCode(_, _))
              .flatten
          case CheckVariant(enumV, idx, _, famArities) =>
            // if all arities are 0, we use
            // integers to represent,
            // otherwise, we use tuples with the first
            // item being the variant
            val useInts = famArities.forall(_ == 0)
            loop(enumV, slotName).flatMap { tup =>
              Env.onLast(tup) { t =>
                (if (useInts) {
                  // this is represented as an integer
                  t =:= idx
                }
                else
                  t.get(0) =:= idx).simplify
              }
            }
          case SetMut(LocalAnonMut(mut), expr) =>
            (Env.nameForAnon(mut), loop(expr, slotName))
              .mapN { (ident, result) =>
                Env.onLast(result) { resx =>
                  (ident := resx).withValue(Code.Const.True)
                }
              }
              .flatten
          case MatchString(str, pat, binds) =>
            (loop(str, slotName), binds.traverse { case LocalAnonMut(m) => Env.nameForAnon(m) })
              .mapN { (strVL, binds) =>
                Env.onLastM(strVL)(matchString(_, pat, binds))
              }
              .flatten
          case SearchList(locMut, init, check, optLeft) =>
            // check to see if we can find a non-empty
            // list that matches check
            (loop(init, slotName), boolExpr(check, slotName))
              .mapN { (initVL, checkVL) =>
                searchList(locMut, initVL, checkVL, optLeft)
              }
              .flatten
        }

      def matchString(strEx: Expression, pat: List[StrPart], binds: List[Code.Ident]): Env[ValueLike] = {
        import StrPart.{LitStr, Glob, CharPart}
        val bindArray = binds.toArray
        // return a value like expression that contains the boolean result
        // and assigns all the bindings along the way
        def loop(offsetIdent: Code.Ident, pat: List[StrPart], next: Int): Env[ValueLike] =
          pat match {
            case Nil =>
              //offset == str.length
              Env.pure(offsetIdent =:= strEx.len())
            case LitStr(expect) :: tail =>
              //val len = expect.length
              //str.regionMatches(offset, expect, 0, len) && loop(offset + len, tail, next)
              //
              // strEx.startswith(expect, offsetIdent)
              loop(offsetIdent, tail, next)
                .flatMap { loopRes =>
                  val regionMatches = strEx.dot(Code.Ident("startswith"))(expect, offsetIdent)
                  val rest =
                    (
                      offsetIdent := offsetIdent + expect.codePointCount(0, expect.length)
                    ).withValue(loopRes)

                  Env.andCode(regionMatches, rest)
                }
            case (c: CharPart) :: tail =>
              val matches = offsetIdent :< strEx.len()
              val n1 = if (c.capture) (next + 1) else next
              val stmt =
                if (c.capture) {
                  // b = str[offset]
                  Code.block(
                    bindArray(next) := Code.SelectItem(strEx, offsetIdent),
                    offsetIdent := offsetIdent + 1
                  )
                  .withValue(true)
                }
                else (offsetIdent := offsetIdent + 1).withValue(true)
              for {
                tailRes <- loop(offsetIdent, tail, n1)
                and2 <- Env.andCode(stmt, tailRes)
                and1 <- Env.andCode(matches, and2)
              } yield and1
            case (h: Glob) :: tail =>
              tail match {
                case Nil =>
                  // we capture all the rest
                  Env.pure(
                    if (h.capture) {
                      // b = str[offset:]
                      (bindArray(next) := Code.SelectRange(strEx, Some(offsetIdent), None))
                        .withValue(true)
                    }
                    else Code.Const.True
                  )
                case LitStr(expect) :: tail2 =>
                  // here we have to make a loop
                  // searching for expect, and then see if we
                  // can match the rest of the pattern
                  val next1 = if (h.capture) next + 1 else next

                  /*
                   * this is the scala code for the below
                   * it is in MatchlessToValue but left here
                   * as an aid to read the code below
                   *
                  var start = offset
                  var result = false
                  while (start >= 0) {
                    val candidate = str.indexOf(expect, start)
                    if (candidate >= 0) {
                      // we have to skip the current expect string
                      val candidateOffset = candidate + expect.lenth
                      val check1 = loop(candidateOffset, tail2, next1)
                      if (check1) {
                        // this was a match, write into next if needed
                        if (h.capture) {
                          results(next) = str.substring(offset, candidate)
                        }
                        result = true
                        start = -1
                      }
                      else {
                        // we couldn't match here, try just after candidate
                        start = candidate + 1
                      }
                    }
                    else {
                      // no more candidates
                      start = -1
                    }
                  }
                  result
                  */
                  (Env.newAssignableVar, Env.newAssignableVar, Env.newAssignableVar, Env.newAssignableVar)
                    .mapN { (start, result, candidate, candOffset) =>
                      val searchEnv = loop(candOffset, tail2, next1)

                      def onSearch(search: ValueLike): Env[Statement] =
                        Env.ifElseS(search,
                          {
                            // we have matched
                            val capture = if (h.capture) (bindArray(next) := Code.SelectRange(strEx, Some(offsetIdent), Some(candidate))) else Code.Pass
                            Code.block(
                              capture,
                              result := true,
                              start := -1
                            )
                          },
                          {
                            // we couldn't match at start, advance just after the
                            // candidate
                            start := candidate + 1
                          })

                      def findBranch(search: ValueLike): Env[Statement] =
                        onSearch(search)
                          .flatMap { onS =>
                            Env.ifElseS(
                              candidate :> -1,
                              // update candidate and search
                              Code.block(
                                candOffset := candidate + expect.codePointCount(0, expect.length),
                                onS)
                              ,
                              // else no more candidates
                              start := -1
                            )
                          }

                      for {
                        search <- searchEnv
                        find <- findBranch(search)
                      } yield
                        (Code.block(
                          start := offsetIdent,
                          result := false,
                          Code.While((start :> -1),
                            Code.block(
                              candidate := strEx.dot(Code.Ident("find"))(expect, start),
                              find
                            )
                          )
                        )
                        .withValue(result))
                    }
                    .flatten
                case (_: CharPart) :: _ =>
                  val next1 = if (h.capture) (next + 1) else next
                  for {
                    matched <- Env.newAssignableVar
                    off1 <- Env.newAssignableVar
                    tailMatched <- loop(off1, tail, next1)    

                    matchStmt = Code.block(
                      matched := false,
                      off1 := offsetIdent,
                      Code.While((!matched).evalAnd(off1 :< strEx.len()),
                        matched := tailMatched // the tail match increments the 
                      )
                    ).withValue(matched)  

                    fullMatch <-
                      if (!h.capture) Env.pure(matchStmt)
                      else {
                        val capture = Code.block(
                          bindArray(next) := Code.SelectRange(strEx, Some(offsetIdent), Some(off1))
                         ).withValue(true)
                        Env.andCode(matchStmt, capture)
                      }

                  } yield fullMatch
                // $COVERAGE-OFF$
                case (_: Glob) :: _ =>
                  throw new IllegalArgumentException(s"pattern: $pat should have been prevented: adjacent globs are not permitted (one is always empty)")
                // $COVERAGE-ON$
              }
          }

        for {
          offsetIdent <- Env.newAssignableVar
          res <- loop(offsetIdent, pat, 0)
        } yield (offsetIdent := 0).withValue(res)
      }

      def searchList(locMut: LocalAnonMut, initVL: ValueLike, checkVL: ValueLike, optLeft: Option[LocalAnonMut]): Env[ValueLike] = {
        /*
         * here is the implementation from MatchlessToValue
         *
            Dynamic { (scope: Scope) =>
              var res = false
              var currentList = initF(scope)
              var leftList = VList.VNil
              while (currentList ne null) {
                currentList match {
                  case nonempty@VList.Cons(head, tail) =>
                    scope.updateMut(mutV, nonempty)
                    scope.updateMut(left, leftList)
                    res = checkF(scope)
                    if (res) { currentList = null }
                    else {
                      currentList = tail
                      leftList = VList.Cons(head, leftList)
                    }
                  case _ =>
                    currentList = null
                    // we don't match empty lists
                }
              }
              res
            }
        */
       (Env.nameForAnon(locMut.ident), optLeft.traverse { lm => Env.nameForAnon(lm.ident) }, Env.newAssignableVar, Env.newAssignableVar)
         .mapN { (currentList, optLeft, res, tmpList) =>
            Code
              .block(
                res := Code.Const.False,
                tmpList := initVL,
                optLeft.fold(Code.pass)(_ := emptyList),
                // we don't match empty lists, so if currentList reaches Empty we are done
                Code.While(isNonEmpty(tmpList),
                  Code.block(
                      currentList := tmpList,
                      res := checkVL,
                      Code.ifElseS(
                        res,
                        tmpList := emptyList,
                        {
                          Code.block(
                            tmpList := tailList(tmpList),
                            optLeft.fold(Code.pass) { left =>
                              left := consList(headList(currentList), left)
                            }
                          )
                        })
                    )
                  )
              ).withValue(res)
         }
      }

      // if expr is a LoopFn or Lambda handle it
      def topFn(name: Code.Ident, expr: FnExpr, slotName: Option[Code.Ident]): Env[Statement] =
        expr match {
          case LoopFn(captures, _, args, b) =>
            // note, name is already bound
            // args can use topFn
            val boundA = args.traverse(Env.topLevelName)
            val subsA = args.traverse { a =>
              for {
                mut <- Env.newAssignableVar
                _ <- Env.subs(a, mut)
              } yield (a, mut)
            }

            for {
              as <- boundA
              subs <- subsA
              subs1 = as.zipWith(subs) { case (b, (_, m)) => (b, m) }
              (binds, body) <- makeSlots(captures, slotName)(loop(b, _))
              loopRes <- Env.buildLoop(name, subs1, body)
              // we have bound this name twice, once for the top and once for substitution
              _ <- subs.traverse_ { case (a, _) => Env.unbind(a) }
            } yield Code.blockFromList(binds.toList ::: loopRes :: Nil)

          case Lambda(captures, _, args, body) =>
            // we can ignore name because python already allows recursion
            // we can use topLevelName on makeDefs since they are already
            // shadowing in the same rules as bosatsu
            (args.traverse(Env.topLevelName(_)), makeSlots(captures, slotName)(loop(body, _)))
              .mapN {
                case (as, (slots, body)) =>
                  Code.blockFromList(
                    slots.toList :::
                    Env.makeDef(name, as, body) ::
                    Nil
                  )
              }
        }

      def makeSlots[A](captures: List[Expr],
        slotName: Option[Code.Ident])(fn: Option[Code.Ident] => Env[A]): Env[(Option[Statement], A)] =
        if (captures.isEmpty) fn(None).map((None, _))
        else {
          for {
            slots <- Env.newAssignableVar
            capVals <- captures.traverse(loop(_, slotName))
            resVal <- fn(Some(slots))
            tup <- Env.onLasts(capVals)(Code.MakeTuple(_))
          } yield (Some(slots := tup), resVal)
        }

      def loop(expr: Expr, slotName: Option[Code.Ident]): Env[ValueLike] =
        expr match {
          case Lambda(captures, _, args, res) =>
            // we ignore name because python already supports recursion
            // we can use topLevelName on makeDefs since they are already
            // shadowing in the same rules as bosatsu
            (args.traverse(Env.topLevelName(_)), makeSlots(captures, slotName)(loop(res, _)))
              .flatMapN {
                case (args, (None, x: Expression)) =>
                  Env.pure(Code.Lambda(args.toList, x))
                case (args, (prefix, v)) =>
                  for {
                    defName <- Env.newAssignableVar
                    defn = Env.makeDef(defName, args, v)
                    block = Code.blockFromList(prefix.toList ::: defn :: Nil)
                  } yield block.withValue(defName)
              }
          case LoopFn(captures, thisName, args, body) =>
            // note, thisName is already bound because LoopFn
            // is a lambda, not a def

            // we can use topLeft for arg names
            val boundA = args.traverse(Env.topLevelName)
            val subsA = args.traverse { a =>
              for {
                mut <- Env.newAssignableVar
                _ <- Env.subs(a, mut)
              } yield (a, mut)
            }

            for {
              nameI <- Env.deref(thisName)
              as <- boundA
              subs <- subsA
              (prefix, body) <- makeSlots(captures, slotName)(loop(body, _))
              subs1 = as.zipWith(subs) { case (b, (_, m)) => (b, m) }
              loopRes <- Env.buildLoop(nameI, subs1, body)
              // we have bound the args twice: once as args, once as interal muts
              _ <- subs.traverse_ { case (a, _) => Env.unbind(a) }
            } yield Code.blockFromList(prefix.toList :+ loopRes).withValue(nameI)

          case PredefExternal((fn, arity)) =>
            // make a lambda
            PredefExternal.makeLambda(arity)(fn)
          case Global(p, n) =>
            remap(p, n)
              .flatMap {
                case Some(v) => Env.pure(v)
                case None =>
                  if (p == packName) {
                    // This is just a name in the local package
                    Env.topLevelName(n)
                  }
                  else {
                    (Env.importPackage(p), Env.topLevelName(n)).mapN(Code.DotSelect(_, _))
                  }
              }
          case Local(b) => Env.deref(b)
          case LocalAnon(a) => Env.nameForAnon(a)
          case LocalAnonMut(m) => Env.nameForAnon(m)
          case ClosureSlot(idx) =>
            slotName match {
              case Some(ident) => Env.pure(ident.get(idx))
              case None =>
                // $COVERAGE-OFF$
                // this should be impossible for well formed Matchless AST
                throw new IllegalStateException(s"saw $expr when there is no defined slot")
                // $COVERAGE-ON$
            }
          case App(PredefExternal((fn, _)), args) =>
            args
              .toList
              .traverse(loop(_, slotName))
              .flatMap(fn)
          case App(cons: ConsExpr, args) =>
            args.traverse(loop(_, slotName)).flatMap { pxs => makeCons(cons, pxs.toList) }
          case App(expr, args) =>
            (loop(expr, slotName), args.traverse(loop(_, slotName)))
              .mapN { (fn, args) =>
                Env.onLasts(fn :: args.toList) {
                  case fn :: args => Code.Apply(fn, args)
                  case other =>
                    // $COVERAGE-OFF$
                    throw new IllegalStateException(s"got $other, expected to match $expr")
                    // $COVERAGE-ON$
                }
              }
              .flatten
          case Let(localOrBind, fn: FnExpr, in) =>
            val inF = loop(in, slotName)

            localOrBind match {
              case Right((b, _)) =>
                // for fn, bosatsu doesn't allow bind name
                // shadowing, so the bind order of the name
                // doesn't matter
                for {
                  bi <- Env.bind(b)
                  tl <- topFn(bi, fn, slotName)
                  ine <- inF
                  _ <- Env.unbind(b)
                } yield tl.withValue(ine)
              case Left(LocalAnon(l)) =>
                // anonymous names never shadow
                Env.nameForAnon(l)
                  .flatMap { bi =>
                    val v = topFn(bi, fn, slotName)
                    (v, inF).mapN(_.withValue(_))
                  }
            }
          case Let(localOrBind, notFn, in) =>
            // we know that notFn is not FnExpr here
            val inF = loop(in, slotName)

            localOrBind match {
              case Right((b, rec)) =>
                if (rec.isRecursive) {
                  // value b is in scope first
                  for {
                    bi <- Env.bind(b)
                    v <- loop(notFn, slotName)
                    ine <- inF
                    _ <- Env.unbind(b)
                  } yield ((bi := v).withValue(ine))
                }
                else {
                  // value b is in scope after ve
                  for {
                    ve <- loop(notFn, slotName)
                    bi <- Env.bind(b)
                    ine <- inF
                    _ <- Env.unbind(b)
                  } yield ((bi := ve).withValue(ine))
                }
              case Left(LocalAnon(l)) =>
                // anonymous names never shadow
                (Env.nameForAnon(l), loop(notFn, slotName))
                  .flatMapN { (bi, vE) =>
                    inF.map((bi := vE).withValue(_))
                  }
            }

          case LetMut(LocalAnonMut(_), in) =>
            // we could delete this name, but
            // there is no need to
            loop(in, slotName)
          case Literal(lit) => Env.pure(Code.litToExpr(lit))
          case If(cond, thenExpr, elseExpr) =>
            def combine(expr: Expr): (List[(BoolExpr, Expr)], Expr) =
              expr match {
                case If(c1, t1, e1) =>
                  val (ifs, e2) = combine(e1)
                  (ifs :+ ((c1, t1)), e2)
                case last => (Nil, last)
              }

            val (rest, last) = combine(elseExpr)
            val ifs = NonEmptyList((cond, thenExpr), rest)

            val ifsV = ifs.traverse { case (c, t) =>
              (boolExpr(c, slotName), loop(t, slotName)).tupled
            }

            (ifsV, loop(last, slotName))
              .mapN { (ifs, elseV) =>
                Env.ifElse(ifs, elseV)
              }
              .flatten

          case Always(cond, expr) =>
            (boolExpr(cond, slotName).map(Code.always), loop(expr, slotName))
              .mapN(_.withValue(_))

          case GetEnumElement(expr, _, idx, _) =>
            // nonempty enums are just structs with the first element being the variant
            // we could assert the v matches when debugging, but typechecking
            // should assure this
            loop(expr, slotName).flatMap { tup =>
              Env.onLast(tup)(_.get(idx + 1))
            }
          case GetStructElement(expr, idx, sz) =>
            val exprR = loop(expr, slotName)
            if (sz == 1) {
              // we don't bother to wrap single item structs
              exprR
            }
            else {
              // structs are just tuples
              exprR.flatMap { tup =>
                Env.onLast(tup)(_.get(idx))
              }
            }
          case PrevNat(expr) =>
            // Nats are just integers
            loop(expr, slotName).flatMap { nat =>
              Env.onLast(nat)(_.evalMinus(Code.Const.One))
            }
          case cons: ConsExpr => makeCons(cons, Nil)
        }
    }
  }
}
