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

import scala.language.experimental.macros
import scala.reflect.macros.blackbox

class UnionMacros(val c: blackbox.Context) extends MacroDefs {
  import Union.Completeness
  import c.universe._

  case class MatchResult(cse: CaseDef, ut: c.Type, pt: AndPos[PatternType])

  final val NothingTpe = typeOf[Nothing]
  final val TheUnion = typeOf[|[_, _]]
  final val TheNull = Literal(Constant(null))
  def ReturnNull[A]: c.Expr[A] = c.Expr[A](TheNull)

  def isPartOfImpl[A, U <: Union](implicit A: c.WeakTypeTag[A], U: c.WeakTypeTag[U]): c.Expr[isPartOf[A, U]] = {
    val unionTypes = expandUnion(U.tpe, TheUnion)
    val tpe = A.tpe.dealias
    if (!isTypePartOf(tpe, unionTypes)) {
      fail(s"$tpe is not a member of ${typeMsg(unionTypes)}.")
    }
    ReturnNull
  }

  def containsSomeOfImpl[U <: Union, T <: Union](implicit U: c.WeakTypeTag[U], T: c.WeakTypeTag[T]): c.Expr[containsSomeOf[U, T]] = {
    val smaller = expandUnion(U.tpe, TheUnion)
    val bigger = expandUnion(T.tpe, TheUnion)
    val msgs = containsOf(smaller, bigger)
    if (msgs.nonEmpty) {
      err(msgs.mkString("\n", "\n", "\n"))
    }
    ReturnNull
  }

  def containsAllOfImpl[U <: Union, T <: Union](implicit U: c.WeakTypeTag[U], T: c.WeakTypeTag[T]): c.Expr[containsAllOf[U, T]] = {
    val smaller = expandUnion(U.tpe, TheUnion)
    val bigger = expandUnion(T.tpe, TheUnion)
    val msgs = containsOf(smaller, bigger) ++ containsOf(bigger, smaller)
    if (msgs.nonEmpty) {
      err(msgs.mkString("\n", "\n", "\n"))
    }
    ReturnNull
  }

  def checkPF[U, T, SU, All <: Completeness, Sub <: Completeness](msg: c.Expr[PartialFunction[Any, T]])(implicit U: c.WeakTypeTag[U], T: c.WeakTypeTag[T], SU: c.WeakTypeTag[SU], All: c.WeakTypeTag[All], Sub: c.WeakTypeTag[Sub]): Tree = {
    val unionTypes = expandUnion(U.tpe, TheUnion)

    def checkPatternType(cse: CaseDef)(pt: AndPos[PatternType]) = {
      val matches = for (ut ← unionTypes if pt.x.matches(ut)) yield MatchResult(cse, ut, pt)
      if (matches.isEmpty) {
        c.error(pt.pos, s"Pattern involving [${pt.x.pt}] is not covered by union ${typeMsg(unionTypes)}.")
      }
      matches
    }

    val cases = msg.tree match {
      case q"{ case ..$cases }" ⇒ cases.toList
      case x                    ⇒ c.abort(x.pos, "Unioned must be used with a partial function literal syntax.")
    }

    val patterns = cases flatMap {
      case cse@CaseDef(pattern, guard, expr) ⇒ patternTypes(pattern).flatMap(checkPatternType(cse))
      case x                                 ⇒ c.error(x.pos.asInstanceOf[c.Position], unknownMsg); Nil
    }

    if (isEffectively[Completeness.Total](All.tpe)) {
      checkTotalCoverage(unionTypes, patterns)
    }

    if (isEffectively[Completeness.Total](Sub.tpe)) {
      val shouldCheck: (Type) ⇒ Boolean =
        if (SU.tpe =:= NothingTpe) scala.Function.const(true)
        else {
          val subUnion = expandUnion(SU.tpe, TheUnion)
          val diff = subUnion.diff(unionTypes)
          if (diff.nonEmpty) {
            c.abort(c.enclosingPosition,
              s"Can't check exhaustiveness for ${typeMsg(diff)} as they do not belong to ${typeMsg(unionTypes)}")
          }
          membership(subUnion)
        }
      checkExhaustiveness(patterns, shouldCheck)
    }

    msg.tree
  }

  def isTypePartOf(tpe: Type, union: List[Type]): Boolean =
    if (tpe =:= NothingTpe || tpe == NoType) true
    else union.exists(ut ⇒ typeMatch(tpe, ut))

  def containsOf(left: List[Type], right: List[Type]): List[String] = {
    val notInRight = left.filterNot(e ⇒ isTypePartOf(e, right))
    failMsgFor(right, notInRight)
  }

  private val unknownMsg       =
    "Pattern is not recognized. If you think this is a bug, please file an " +
    "issue at Github."
  private val nonExhaustiveMsg =
    "The patterns for %1$s are not exhaustive; It would fail on the following " +
    "%2$s. Note that this check may be a false negative. If that is the case, " +
    "workaround by adding a catch-all pattern like `case _: %1$s` to your " +
    "cases and please file an issue on Github."


  private def checkTotalCoverage[Sub <: Completeness, All <: Completeness, T, U](unionTypes: List[c.universe.Type], patterns: List[MatchResult]): Unit = {
    val matched = patterns.map(_.ut)
    val unmatched = unionTypes.filterNot(membership(matched))
    if (unmatched.nonEmpty) {
      val prefix = plural(unmatched, s"${unmatched.head}", s"these types: ${typeMsg(unmatched)}")
      val msg = s"The partial function fails to match on $prefix."
      c.error(c.enclosingPosition, msg)
    }
  }

  private def checkExhaustiveness(allPatterns: List[MatchResult], shouldCheck: Type ⇒ Boolean): Unit = {
    val global = c.universe.asInstanceOf[tools.nsc.Global]
    val gpos = c.enclosingPosition.asInstanceOf[global.Position]
    val typer = global.patmat.global.analyzer.newTyper(global.analyzer.rootContext(global.NoCompilationUnit, global.EmptyTree))
    val translator = new global.patmat.OptimizingMatchTranslator(typer)
    val copier = newStrictTreeCopier // https://youtu.be/gqSBM_kLJaI?t=21m35s
    val asg = (t: Tree) ⇒ t.asInstanceOf[global.Tree]

    allPatterns.groupBy(_.ut).foreach {case (base, patterns) ⇒
      if (shouldCheck(base)) {

        val tp = base.asInstanceOf[global.Type]
        val scrutSym = translator.freshSym(gpos, tp)
        val cases = patterns map {pat ⇒

          // workaround https://issues.scala-lang.org/browse/SI-5464
          // And they said it can't be done. In their faces, ha!
          // Many thanks to @Chasmo90/@MartinSeeler for the hint
          val newT = c.internal.transform(pat.cse.pat)((t, tapi) ⇒ t match {
            case Bind(name, body) ⇒ copier.Bind(t, c.freshName(name), body) // Bind(c.freshName(name), body)
            case otherwise        ⇒ tapi.default(t)
          })

          val newPat = typer.typedPattern(global.resetAttrs(asg(newT)), tp)
          if (newPat == EmptyTree) {
            c.error(pat.cse.pos, s"Failed to type ${pat.cse.pat} as $base. This a bug in typed-actors.")
            Nil
          } else {
            // deliberatly leave out guards as they disable exhaustiveness checks
            val gcse = global.CaseDef(newPat, asg(pat.cse.body))
            translator.translateCase(scrutSym, tp)(gcse)
          }
        }

        val counterExamples = translator.exhaustive(scrutSym, cases, tp)
        val improvedCounterExamples = if (counterExamples.isEmpty) {
          // Scalas checker reports exhaustivity, but we can improve on some cases.
          // mostly for literals where we dont have a match all case.
          // note that flat mapping all of expr is mostly wrong for recursive
          // types like ::, but we expect Scala to have covered these.
          // If not, well, good luck then.
          val exprs = patterns.flatMap(_.pt.x.expr)
          if (!exprs.exists(e ⇒ typeMatch(e, base, variant = false))) {
            List(s"$base not ${typeMsg(exprs)}")
          } else {
            Nil
          }
        } else {
          counterExamples
        }
        if (improvedCounterExamples.nonEmpty) {
          val msg = plural(improvedCounterExamples,
            s"input: ${improvedCounterExamples.head}",
            s"inputs: ${improvedCounterExamples.mkString(", ")}")
          c.error(c.enclosingPosition, String.format(nonExhaustiveMsg, base, msg))
        }
      }
    }
  }

  private def failMsgFor(target: List[Type], diff: List[Type]): List[String] =
    if (diff.isEmpty) Nil else {
      val tgts = typeMsg(target)
      diff.map(d ⇒ s"$d is not in $tgts.")
    }

  /**
   * get a more precise representation than just simply `tpe.finalResultType`
   * especially:
   * - tuples like `(_: String, _: Int)` will return `(String, Int)` instead of `(Any, Any)`
   * - wildcards will return `notype` instead of `Any` to keep the wildcard semantics
   * - alternatives like `"foo" | 42` will return `List(String, Int)` instead of `Any`
   */
  private def patternTypes(pattern: c.Tree): List[AndPos[PatternType]] = pattern match {
    case Bind(_, patt)             ⇒
      patternTypes(patt)
    case Apply(tt: TypeTree, args) ⇒
      resolveApply(tt.tpe, args, tt.pos)
    case a@Apply(_, args)          ⇒
      resolveUnapply(a.tpe, args, a.pos)
    case u@UnApply(_, args)        ⇒
      resolveUnapply(u.tpe, args, u.pos)
    case Star(tree)                ⇒
      patternTypes(tree)
    case Alternative(patterns)     ⇒
      patterns.flatMap(p ⇒ patternTypes(p))
    case Typed(expr, tpt)          ⇒
      AndPos(PatternType(tpt.tpe, patternTypes(expr).head.x.expr), tpt.pos) :: Nil // TODO: expr might just be NoType
    case s@Select(_, _)            ⇒
      AndPos(PatternType(s.tpe), s.pos) :: Nil
    case i@Ident(_)                ⇒
      AndPos(PatternType(NoType), i.pos) :: Nil
    case l@Literal(const)          ⇒
      AndPos(PatternType(c.internal.constantType(const)), l.pos) :: Nil
    case x                         ⇒
      c.abort(x.pos, unknownMsg)
  }

  private def resolveUnapply(tpe: c.Type, args: List[c.Tree], pos: c.Position): List[AndPos[PatternType]] = {
    if (tpe.typeArgs.size != 1) {
      c.abort(pos, "Unapply is currently only supported for types with one type parameter.")
    }
    val subs = args.flatMap(arg ⇒ patternTypes(arg).map(_.x))
    val applied = lubapply(tpe, List(lubPats(subs)))
    AndPos(applied, pos) :: Nil
  }

  private def lubapply(tpe: Type, subs: List[PatternType]): PatternType = {
    val ls = subs.map(_.pt)
    val pt = appliedType(tpe, ls)
    val expr = for (s ← subs; e ← s.expr) yield appliedType(tpe, e)
    PatternType(pt, expr)
  }

  private def lubs[A](xs: List[A])(f: A ⇒ c.Type): c.Type =
    lub(xs.withFilter(x ⇒ f(x) != NoType).map(f))

  private def resolveApply(tpe: c.Type, args: List[c.Tree], pos: c.Position): List[AndPos[PatternType]] =
    AndPos(resolveType(tpe, args), pos) :: Nil

  private def resolveType(tpe: c.Type, args: List[c.Tree]): PatternType = {
    val resultType = tpe.finalResultType
    val typeArgs = mapCaseAccessorsToTypeArgs(resultType)
    if (typeArgs.isEmpty) {
      PatternType(resultType)
    } else {
      val subArgs = args.mapWithIndex(concretiseArgument(typeArgs, resultType))
      val concreteTypeArgs = resultType.typeArgs.indices.map(lubTypeArgs(subArgs)).toList
      lubapply(resultType.typeConstructor, concreteTypeArgs)
    }
  }

  private def lubTypeArgs(subArgs: List[TypeArg])(typeArgIndex: Int): PatternType = {
    subArgs.toList.collect {
      case TypeArg(_, _, `typeArgIndex`, _, _, Some(p)) ⇒ p
    }.first.fold(ts ⇒ lubPats(ts), _.getOrElse(PatternType(NoType)))
  }

  private def lubPats(xs: List[PatternType]): PatternType =
    PatternType(lubs(xs)(_.pt), xs.flatMap(_.expr))


  private def concretiseArgument(typeArgs: List[TypeArg], resultType: c.Type)(pattern: c.Tree, applyIndex: Int): TypeArg = {
    val associated = typeArgs.find(_.posInApply == applyIndex)
      .getOrElse(c.abort(pattern.pos, s"Cannot find an according type definition in $resultType"))
    val proper = patternTypes(pattern).first(_.x).right.flatMap {ot ⇒
      if (associated.directMatch) Right(ot)
      else ot match {
        case None    ⇒ Right(None)
        case Some(x) ⇒
          val aligned = alignArgument(x.pt, associated.field, associated.arg, pattern.pos)
          val alignedExpr = x.expr.map(alignArgument(_, associated.field, associated.arg, pattern.pos))
          Right(Some(PatternType(aligned, alignedExpr)))
      }
    }.fold(
      found ⇒ c.abort(pattern.pos, s"Pattern yields multiple types ($found), only one is expected."),
      _.getOrElse(PatternType(NoType))
    )
    associated.concrete(proper)
  }

  private def alignArgument(tpe: Type, to: Type, ptr: TypeArgPointer, pos: Position): Type = {
    if (tpe == NoType) NoType
    else {
      val aligned = alignTypeTo(tpe, to)
      applyTypeArg(aligned, ptr, pos)
    }
  }

  private def typeMsg(types: List[Type]) =
    types.mkString("{", " | ", "}")

  private def plural(lst: List[_], ifOne: ⇒ String, ifMore: ⇒ String) =
    if (lst.nonEmpty && lst.tail.nonEmpty) ifMore else ifOne

}