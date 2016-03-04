/*
 * Copyright 2015 – 2016 Paul Horn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.knutwalker.union

import de.knutwalker.TripleArrow

import org.specs2.mutable.Specification
import shapeless.test.illTyped


object UnionSpec extends Specification with TripleArrow {
  final val X  = 1337
  final val XS = "1337"
  val y  = 4242
  val ys = "4242"

  trait Foo1[X]
  case class Bar1[A](x: A) extends Foo1[Option[A]]

  trait Foo2[X, Y]
  case class Bar2[A, B](x: B) extends Foo2[B, A]
  case class Baz2[A, B](x: B) extends Foo2[Option[B], A]

  trait Foo3[X, Y, Z]
  case class Bar3[A, B](x: B) extends Foo3[B, A, B]
  case class Baz3[A, B](x: B) extends Foo3[B, A, Option[B]]

  type ISB = Int | String | Boolean

  title("Typer for partial functions")
  sequential

  "scalar type" should {
    "positive" should {
      "value" >>> {
        Union[Int] {
          case 42  ⇒ true
          case X   ⇒ true
          case `y` ⇒ true
          case i@21 ⇒ true
        }
      }
      "wildcard" should {
        "type" >>> {
          Union[Int] {
            case i: Int ⇒ true
          }
        }
        "bind" >>> {
          Union[Int] {
            case i ⇒ true
          }
        }
        "wildcard" >>> {
          Union[Int] {
            case _ ⇒ true
          }
        }
      }
    }
    "negative" >>> {
      illTyped("""Union[Int] { case "42" ⇒ true }""")
      illTyped("""Union[Int] { case XS ⇒ true }""")
      illTyped("""Union[Int] { case `ys` ⇒ true }""")
      illTyped("""Union[Int] { case s: String ⇒ true }""")
      illTyped("""Union[Int] { case s @ "42" ⇒ true }""")
    }
  }

  "an applied * -> * type" should {
    "direct relation" should {
      "postive/compilation tests" should {
        "work" >>> {
          Union[List[Int]] {
            case 42 :: Nil       ⇒ true
            case 42 :: rest      ⇒ true
            case X :: Nil        ⇒ true
            case `y` :: Nil      ⇒ true
            case (x@21) :: Nil   ⇒ true
            case (_: Int) :: Nil ⇒ true
            case Nil             ⇒ true
          }
        }

        "work with bind" >>> {
          Union[List[Int]] {
            case List(x) ⇒ true
          }
        }

        "work with wildcard" >>> {
          Union[List[Int]] {
            case List(_) ⇒ true
          }
        }

        "work with star" >>> {
          Union[List[Int]] {
            case List(xs@_*) ⇒ true
          }
        }

        "work with bind and star" >>> {
          Union[List[Int]] {
            case List(x: Int, xs@_*) ⇒ true
          }
        }
      }
    }

    "subtype relation" should {
      "postive/compilation tests" should {
        "work" >>> {
          Union[Option[Int]] {
            case Some(42)     ⇒ true
            case Some(X)      ⇒ true
            case Some(`y`)    ⇒ true
            case Some(x@21)   ⇒ true
            case Some(_: Int) ⇒ true
            case None         ⇒ true
          }
        }
        "work with wildcard bind" >>> {
          Union[Option[Int]] {
            case Some(x) ⇒ true
          }
        }
        "work with wildcard" >>> {
          Union[Option[Int]] {
            case Some(_) ⇒ true
          }
        }
      }

      // TODO: negative tests
    }

    "doubly nested types" should {
      "postive/compilation tests" should {
        "work" >>> {
          Union[List[Option[Double]]] {
            case Some(13.37) :: Some(42.0) :: Nil ⇒ true
          }
        }
      }
    }

    "subtypes with different shapes" should {
      "postive/compilation tests" should {
        "sum type shape" >>> {
          Union[Either[String, Int]] {
            case Left("42")      ⇒ true
            case Left(_: String) ⇒ true
            case Right(42)       ⇒ true
            case Right(X)        ⇒ true
            case Right(`y`)      ⇒ true
            case Right(x@21)     ⇒ true
            case Right(_: Int)   ⇒ true
          }
        }

        "applied type constructor" >>> {
          Union[Foo1[Option[Int]]] {
            case Bar1(42)     ⇒ true
            case Bar1(X)      ⇒ true
            case Bar1(`y`)    ⇒ true
            case Bar1(x@21)   ⇒ true
            case Bar1(_: Int) ⇒ true
          }
        }

        "different order" >>> {
          Union[Foo2[Int, String]] {
            case Bar2(42)     ⇒ true
            case Bar2(X)      ⇒ true
            case Bar2(`y`)    ⇒ true
            case Bar2(x@21)   ⇒ true
            case Bar2(_: Int) ⇒ true
          }
        }

        "different order with applied type constructor" >>> {
          Union[Foo2[Option[Int], String]] {
            case Baz2(42)     ⇒ true
            case Baz2(X)      ⇒ true
            case Baz2(`y`)    ⇒ true
            case Baz2(x@21)   ⇒ true
            case Baz2(_: Int) ⇒ true
          }
        }

        "type repetitions" >>> {
          Union[Foo3[Int, String, Int]] {
            case Bar3(42)     ⇒ true
            case Bar3(X)      ⇒ true
            case Bar3(`y`)    ⇒ true
            case Bar3(x@21)   ⇒ true
            case Bar3(_: Int) ⇒ true
          }
        }

        "type repetitions with applied type constructor" >>> {
          Union[Foo3[Int, String, Option[Int]]] {
            case Baz3(42)     ⇒ true
            case Baz3(X)      ⇒ true
            case Baz3(`y`)    ⇒ true
            case Baz3(x@21)   ⇒ true
            case Baz3(_: Int) ⇒ true
          }
        }
      }

      "negative/non-compilation tests" >>> {
        illTyped("""Union[Either[String, Boolean]] { case Left(b: Boolean) ⇒ true }""")
        illTyped("""Union[Either[String, Boolean]] { case Right(s: String) ⇒ true }""")
        illTyped("""Union[Foo1[Option[Int]]] { case Bar1(s: String) ⇒ true }""")
        illTyped("""Union[Foo2[Int, String]] { case Bar2(s: String) ⇒ true }""")
        illTyped("""Union[Foo2[Option[Int], String]] { case Baz2(s: String) ⇒ true }""")
        illTyped("""Union[Foo3[Int, String, Int]] { case Bar3(s: String) ⇒ true }""")
        illTyped("""Union[Foo3[Int, String, Option[Int]]] { case Bar3(s: String) ⇒ true }""")
      }
    }

    "unapply" should {
      "postive/compilation tests" should {
        "unapply" >>> {
          Union[List[Int]] {
            case List(42)     ⇒ true
            case List(X)      ⇒ true
            case List(`y`)    ⇒ true
            case List(x@21)   ⇒ true
            case List(_: Int) ⇒ true
            case Nil          ⇒ true
          }
        }

        "unapply with bind" >>> {
          Union[List[Int]] {
            case List(x) ⇒ true
          }
        }

        "unapply with wildcard" >>> {
          Union[List[Int]] {
            case List(_) ⇒ true
          }
        }

        "unapply with star" >>> {
          Union[List[Int]] {
            case List(xs@_*) ⇒ true
          }
        }

        "unapply with bind and star" >>> {
          Union[List[Int]] {
            case List(x: Int, xs@_*) ⇒ true
          }
        }
      }
    }
  }

  "a Union type" should {
    "multiple direct values" should {
      "postive/compilation tests" should {
        "plain match" >>> {
          Union[Int | String | Boolean] {
            case s: String  ⇒ true
            case i: Int     ⇒ true
            case b: Boolean ⇒ true
          }
        }
        "alternative" >>> {
          Union[Int | String | Boolean] {
            case 42 | "42" | true ⇒ true
          }
        }
        "by alias" >>> {
          Union[ISB] {
            case s: String  ⇒ true
            case i: Int     ⇒ true
            case b: Boolean ⇒ true
          }
        }
      }

      "negative/non-compilation tests" >>> {
        illTyped("""Union[ISB] { case 13.37 ⇒ true }""")
        illTyped("""Union[ISB] { case Some(s: String) ⇒ true }""")
        illTyped("""Union[ISB] { case None ⇒ true }""")
        illTyped("""Union[ISB] { case Some(_) ⇒ true }""")
      }
    }
    "multiple applied hk types" should {
      "postive/compilation tests" should {
        "plain match" >>> {
          Union[Option[Int] | Either[Boolean, String]] {
            case Some(i: Int)     ⇒ true
            case None             ⇒ true
            case Left(b: Boolean) ⇒ true
            case Right(s: String) ⇒ true
          }
        }
      }

      "negative/non-compilation tests" >>> {
        illTyped("""Union[Option[Int] | Either[Boolean, String]] { case Some(s: String) ⇒ true }""")
        illTyped("""Union[Option[Int] | Either[Boolean, String]] { case Left(s: String) ⇒ true }""")
        illTyped("""Union[Option[Int] | Either[Boolean, String]] { case Right(b: Boolean) ⇒ true }""")
      }
    }
  }
}
