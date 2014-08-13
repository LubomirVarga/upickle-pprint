package upickle

import scala.reflect.macros._
import scala.reflect._
import scala.annotation.{ClassfileAnnotation, StaticAnnotation}

//import acyclic.file
/**
 * Used to annotate either case classes or their fields, telling uPickle
 * to use a custom string as the key for that class/field rather than the
 * default string which is the full-name of that class/field.
 */
class key(s: String) extends StaticAnnotation

/**
 * Implementation of macros used by uPickle to serialize and deserialize
 * case classes automatically. You probably shouldn't need to use these
 * directly, since they are called implicitly when trying to read/write
 * types you don't have a Reader/Writer in scope for.
 */
object Macros {

  class RW(val short: String, val long: String, val actionName: String)
  object RW{
    object R extends RW("R", "Reader", "apply")
    object W extends RW("W", "Writer", "unapply")
  }
  def macroRImpl[T: c.WeakTypeTag](c: Context) = {
    import c.universe._
    val tpe = weakTypeTag[T].tpe
    c.Expr[Reader[T]]{
      val x = picklerFor(c)(tpe, RW.R)(
        _.map(p => q"$p.read": Tree)
         .reduce((a, b) => q"$a orElse $b")
      )

      val msg = "Tagged Object " + tpe.typeSymbol.fullName
      q"""validateReader($msg){$x}"""
    }
  }
  def macroWImpl[T: c.WeakTypeTag](c: Context) = {
    import c.universe._
    val tpe = weakTypeTag[T].tpe
    c.Expr[Writer[T]]{
      picklerFor(c)(tpe, RW.W)(
        _.map(p => q"$p.write": Tree)
         .reduce((a, b) => q"Internal.mergeable($a) merge $b")
      )
    }
  }

  /**
   * Get the custom @key annotation from
   * the parameter Symbol if it exists
   */
  def customKey(c: Context)(sym: c.Symbol): Option[String] = {
    import c.universe._
    sym.annotations
       .find(_.tpe == typeOf[key])
       .flatMap(_.scalaArgs.headOption)
       .map{case Literal(Constant(s)) => s.toString}
  }

  /**
   * Generates a pickler for a particuler Type
   *
   * @param tpe The type we are generating the pickler for
   * @param rw Configuration that determines whether it's a Reader or
   *           a Writer, together with the various names wihich vary
   *           between those two choices
   * @param treeMaker How to merge the trees of the multiple subpicklers
   *                  into one larger tree
   */
  def picklerFor(c: Context)
                (tpe: c.Type, rw: RW)
                (treeMaker: Seq[c.Tree] => c.Tree): c.Tree = {

    import c.universe._
    val clsSymbol = tpe.typeSymbol.asClass

    def annotate(pickler: Tree) = {
      val sealedParent = tpe.baseClasses.find(_.asClass.isSealed)
      sealedParent.fold(pickler){ parent =>
        val index = customKey(c)(tpe.typeSymbol).getOrElse(tpe.typeSymbol.fullName)
        q"Internal.annotate($pickler, $index)"
      }
    }

    tpe.declaration(nme.CONSTRUCTOR) match {
      case NoSymbol if clsSymbol.isSealed => // I'm a sealed trait/class!
        val subPicklers =
          for(subCls <- clsSymbol.knownDirectSubclasses.toSeq) yield {
            picklerFor(c)(subCls.asType.toType, rw)(treeMaker)
          }

        val combined = treeMaker(subPicklers)
        val knotName = newTermName("knot"+rw.short)
        q"""
          Internal.$knotName{implicit i: Knot.${newTypeName(rw.short)}[$tpe] =>
            val x = ${newTermName(rw.long)}[$tpe]($combined)
            i.copyFrom(x)
            x
          }
        """


      case x if tpe.typeSymbol.isModuleClass =>
        val mod = tpe.typeSymbol.asClass.module
        annotate(q"Internal.${newTermName("Case0"+rw.short)}($mod)")

      case x => // I'm a class

        val pickler = {
          val companion =
            tpe.typeSymbol
               .companionSymbol
          val argSyms =
            companion
               .typeSignature
               .member(newTermName("apply"))
               .asMethod
               .paramss
               .flatten

          val args = argSyms.map { p =>
            customKey(c)(p).getOrElse(p.name.toString)
          }

          val rwName = newTermName(s"Case${args.length}${rw.short}")
          val className = newTermName(tpe.typeSymbol.name.toString)
          val actionName = newTermName(rw.actionName)
          val defaults = argSyms.zipWithIndex.map{ case (s, i) =>
            val defaultName = newTermName("apply$default$" + (i + 1))
            companion.typeSignature.member(defaultName) match{
              case NoSymbol => q"null"
              case x => q"writeJs($companion.$defaultName)"
            }
          }
          if (args.length == 0) // 0-arg case classes are treated like `object`s
            q"Internal.${newTermName("Case0"+rw.short)}($companion())"
          else if (args.length == 1 && rw == RW.W) // 1-arg case classes need their output wrapped in a Tuple1
            q"Internal.$rwName(x => $companion.$actionName(x).map(Tuple1.apply), Array(..$args), Array(..$defaults)): ${newTypeName(rw.long)}[$tpe]"
          else // Otherwise, reading and writing are kinda identical
            q"Internal.$rwName($companion.$actionName, Array(..$args), Array(..$defaults)): ${newTypeName(rw.long)}[$tpe]"
        }

        annotate(pickler)
    }
  }
}
 