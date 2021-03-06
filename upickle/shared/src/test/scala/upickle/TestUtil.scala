package upickle
import utest._
import acyclic.file
/**
* Created by haoyi on 4/22/14.
*/
object TestUtil extends TestUtil[upickle.default.type](upickle.default)
object LegacyTestUtil extends TestUtil[upickle.legacy.type](upickle.legacy)
class TestUtil[Api <: upickle.Api](api: Api){
  import api._
  implicit class -->[T](x: T){
    def -->(y: T) = {
      val lhs = x
      val rhs = y
      assert(lhs == rhs)
    }
  }
  def rw[T: Reader: Writer](t: T, s: String*) = {
    rwk[T, T](t, s:_*)(x => x)
  }
  def rwk[T: Reader: Writer, V](t: T, sIn: String*)(k: T => V) = {
    val writtenT = write(t)

    val strings = sIn.map(_.trim)

    if (strings.length > 0)
      assert(strings.map(upickle.json.read).contains(upickle.json.read(writtenT)))

    for (s <- strings) {
      val readS = read[T](s)
      assert(k(readS) == k(t))
    }

    assert(k(read[T](writtenT)) == k(t))
  }
}