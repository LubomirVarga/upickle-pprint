package upickle
import scala.reflect.ClassTag
import upickle._
import acyclic.file

/**
 * Picklite tries the following mechanisms for pickling a type
 *
 * - Is there an implicit pickler for that type?
 * - Does the companion have matching apply/unapply?
 */
class Bundle(val json: JsonImpl) extends Implicits with Generated with Types{

  /**
   * APIs that need to be exposed to the outside world to support Macros
   * which depend on them, but probably should not get used directly.
   */
  object Internal extends InternalUtils with InternalGenerated
}
