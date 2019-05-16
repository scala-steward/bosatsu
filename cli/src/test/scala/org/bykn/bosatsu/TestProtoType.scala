package org.bykn.bosatsu

import cats.Eq
import org.bykn.bosatsu.rankn.NTypeGen
import org.scalatest.prop.PropertyChecks.{ forAll, PropertyCheckConfiguration }
import org.scalatest.FunSuite
import scala.util.Try
import cats.implicits._

class TestProtoType extends FunSuite {
  implicit val generatorDrivenConfig =
    //PropertyCheckConfiguration(minSuccessful = 5000)
    PropertyCheckConfiguration(minSuccessful = 500)
    //PropertyCheckConfiguration(minSuccessful = 5)

  def law[A: Eq, B](a: A, fn: A => Try[B], gn: B => Try[A]) = {
    val maybeProto = fn(a)
    assert(maybeProto.isSuccess, maybeProto.toString)
    val proto = maybeProto.get

    val maybeBack = gn(proto)
    assert(maybeBack.isSuccess, maybeBack.toString)
    val orig = maybeBack.get

    // lazy val diffIdx =
    //   a.toString
    //     .zip(orig.toString)
    //     .zipWithIndex
    //     .dropWhile { case ((a, b), _) => a == b }
    //     .headOption.map(_._2)
    //     .getOrElse(0)

    //assert(Eq[A].eqv(a, orig), s"${a.toString.drop(diffIdx).take(20)} != ${orig.toString.drop(diffIdx).take(20)}")
    assert(Eq[A].eqv(a, orig))
  }

  test("we can roundtrip types through proto") {
    forAll(NTypeGen.genDepth03) { tpe =>
      law(tpe, ProtoConverter.typeToProto _, ProtoConverter.typeFromProto _)(Eq.fromUniversalEquals)
    }
  }

  test("we can roundtrip interface through proto") {
    forAll(Generators.interfaceGen) { iface =>
      law(iface, ProtoConverter.interfaceToProto _, ProtoConverter.interfaceFromProto _)(Eq.fromUniversalEquals)
    }
  }

  test("we can roundtrip interfaces through proto") {
    forAll(Generators.smallList(Generators.interfaceGen)) { ifaces =>
      val sortedEq: Eq[List[Package.Interface]] =
        new Eq[List[Package.Interface]] {
          def eqv(l: List[Package.Interface], r: List[Package.Interface]) =
            // we are only sorting the left because we expect the right
            // to come out sorted
            l.sortBy(_.name.asString) == r
        }
      law(ifaces, ProtoConverter.interfacesToProto[List] _, ProtoConverter.interfacesFromProto _)(sortedEq)
    }
  }
}