package org.bykn.bosatsu.jsui

import cats.effect.{IO, Resource}
import org.bykn.bosatsu.{MemoryMain, Test, rankn}
import org.typelevel.paiges.Doc
import org.scalajs.dom.window.localStorage

import Action.Cmd

object Store {
  val memoryMain = new MemoryMain[Either[Throwable, *], String](_.split("/", -1).toList)

  type HandlerFn = memoryMain.Output => String
  def cmdHandler(cmd: Cmd): (List[String], HandlerFn) =
    cmd match {
      case Cmd.Eval =>
        val args = List(
          "eval", "--input", "root/WebDemo", "--package_root", "root",
          "--main_file", "root/WebDemo", "--color", "html"
        )
      
        val handler: HandlerFn = {
          case memoryMain.Output.EvaluationResult(_, tpe, resDoc) =>
            val tDoc = rankn.Type.fullyResolvedDocument.document(tpe)
            val doc = resDoc.value + (Doc.lineOrEmpty + Doc.text(": ") + tDoc).nested(4)
            doc.render(80)
          case other =>
            s"internal error. got unexpected result: $other"
        }
        (args, handler)
      case Cmd.Test =>
        val args = List(
          "test", "--input", "root/WebDemo", "--package_root", "root",
          "--test_file", "root/WebDemo", "--color", "html"
        )
        val handler: HandlerFn = {
          case memoryMain.Output.TestOutput(resMap, color) =>
            val testReport = Test.outputFor(resMap, color)
            testReport.doc.render(80)
          case other =>
            s"internal error. got unexpected result: $other"
        }
        (args, handler)
    }

  def run(cmd: Cmd, str: String): IO[String] = IO {
    val start = System.currentTimeMillis()
    println(s"starting $cmd: $start")
    val (args, handler) = cmdHandler(cmd)

    val res = memoryMain.runWith(
      files = Map("root/WebDemo" -> str)
    )(args) match {
      case Right(good) => handler(good)
      case Left(err) =>
        memoryMain.mainExceptionToString(err) match {
          case Some(e) => e
          case None => s"unknown error: $err"
        }
    }

    val end = System.currentTimeMillis()
    println(s"finished $cmd in ${end - start}ms")
    res
  }

  def stateSetter(st: State): IO[Unit] =
    IO {
      localStorage.setItem("state", State.stateToJsonString(st))
    }

  def initialState: IO[State] =
    IO(localStorage.getItem("state")).flatMap { init =>
      if (init == null) IO.pure(State.Init)
      else (State.stringToState(init) match {
        case Right(s) => IO.pure(s)
        case Left(err) =>
          IO.println(s"could not deserialize:\n\n$init\n\n$err")
            .as(State.Init)
      })
    }

  val value: Resource[IO, ff4s.Store[IO, State, Action]] =
    for {
    init <- Resource.liftK(initialState)
    store <- ff4s.Store[IO, State, Action](init) { store =>
      {
        case Action.CodeEntered(text) =>
          {
            case State.Init | State.WithText(_) => (State.WithText(text), None)
            case c @ State.Compiling(_) => (c, None)
            case comp @ State.Compiled(_, _, _) => (comp.copy(editorText = text), None)
          }

        case Action.Run(cmd) =>
          {
            case State.Init => (State.Init, None)
            case c @ State.Compiling(_) => (c, None)
            case ht: State.HasText =>
              val action =
                for {
                  _ <- stateSetter(ht)
                  start <- IO.monotonic
                  output <- run(cmd, ht.editorText)
                  end <- IO.monotonic
                  _ <- store.dispatch(Action.CmdCompleted(output, end - start, cmd))
                } yield ()

              (State.Compiling(ht), Some(action))
          }
        case Action.CmdCompleted(result, dur, _) =>
          {
            case State.Compiling(ht) =>
              val next = State.Compiled(ht.editorText, result, dur)
              (next, Some(stateSetter(next)))
            case unexpected =>
              // TODO send some error message
              println(s"unexpected Complete: $result => $unexpected")
              (unexpected, None)
          }
      }  
    }
  } yield store
}