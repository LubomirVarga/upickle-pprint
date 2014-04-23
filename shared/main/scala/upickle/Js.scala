package upickle

import scala.annotation.switch
import scala.Predef._
import scala.Some

object Js {
  sealed trait Value
  case class String(s: java.lang.String) extends Value
  case class Object(kv: Seq[(java.lang.String, Value)]) extends Value
  case class Array(args: Seq[Value]) extends Value
  case class Number(d: java.lang.String) extends Value
  case object False extends Value
  case object True extends Value
  case object Null extends Value
}

object Json {
  def write(v: Js.Value): String = v match {
    case Js.String(s) =>
      val out = s.flatMap {
        case '\\' => "\\\\"
        case '"' => "\\\""
        case '/' => "\\/"
        case '\b' => "\\b"
        case '\t' => "\\t"
        case '\n' => "\\n"
        case '\f' => "\\f"
        case '\r' => "\\r"
        case c if c < ' ' =>
          val t = "000" + Integer.toHexString(c)
          "\\u" + t.takeRight(4)
        case c => c.toString
      }
      '"' + out + '"'
    case Js.Object(kv) =>
      val contents = kv.toIterator.map{
        case (k, v) => s"${write(Js.String(k))}: ${write(v)}"
      }.mkString(", ")
      s"{$contents}"
    case Js.Array(vs) => s"[${vs.map(write).mkString(", ")}]"
    case Js.Number(d) => d
    case Js.False => "false"
    case Js.True => "true"
    case Js.Null => "null"
  }

  /**
   * Self-contained JSON parser adapted from
   *
   * https://github.com/nestorpersist/json
   */
  def read(s: String): Js.Value = {

    // *** Character Kinds

    type CharKind = Int
    val Letter = 0
    val Digit = 1
    val Minus = 2
    val Quote = 3
    val Colon = 4
    val Comma = 5
    val Lbra = 6
    val Rbra = 7
    val Larr = 8
    val Rarr = 9
    val Blank = 10
    val Other = 11
    val Eof = 12
    val Slash = 13

    // *** Token Kinds

    type TokenKind = Int
    val ID = 0
    val STRING = 1
    val NUMBER = 2
    val BIGNUMBER = 3
    val FLOATNUMBER = 4
    val COLON = 5
    val COMMA = 6
    val LOBJ = 7
    val ROBJ = 8
    val LARR = 9
    val RARR = 10
    val BLANK = 11
    val EOF = 12

    // *** Character => CharKind Map ***

    val charKind = (0 to 255).toArray.map {
      case c if 'a'.toInt <= c && c <= 'z'.toInt => Letter
      case c if 'A'.toInt <= c && c <= 'Z'.toInt => Letter
      case c if '0'.toInt <= c && c <= '9'.toInt => Digit
      case '-' => Minus
      case ',' => Comma
      case '"' => Quote
      case ':' => Colon
      case '{' => Lbra
      case '}' => Rbra
      case '[' => Larr
      case ']' => Rarr
      case ' ' => Blank
      case '\t' => Blank
      case '\n' => Blank
      case '\r' => Blank
      case '/' => Slash
      case _ => Other
    }

    // *** Character Escapes

    val escapeMap = Map[Int, String](
      '\\'.toInt -> "\\",
      '/'.toInt -> "/",
      '\"'.toInt -> "\"",
      'b'.toInt -> "\b",
      'f'.toInt -> "\f",
      'n'.toInt -> "\n",
      'r'.toInt -> "\r",
      't'.toInt -> "\t"
    )
    // *** Import Shared Data ***

    // *** INPUT STRING ***

    // array faster than accessing string directly using charAt
    //final  val s1 = s.toCharArray()
    val size = s.size

    // *** CHARACTERS ***

    var pos = 0

    var ch: Int = 0
    var chKind: CharKind = 0
    var chLinePos: Int = 0
    var chCharPos: Int = 0

    def chNext() = {
      if (pos < size) {
        //ch = s1(pos).toInt
        ch = s.charAt(pos)
        chKind = if (ch < 255) {
          charKind(ch)
        } else {
          Other
        }
        pos += 1
        if (ch == '\n'.toInt) {
          chLinePos += 1
          chCharPos = 1
        } else {
          chCharPos += 1
        }
      } else {
        ch = -1
        pos = size + 1
        chKind = Eof
      }
    }


    def chError(msg: String): Nothing = {
      throw new Json.Exception(msg, s, chLinePos, chCharPos)
    }

    def chMark = pos - 1

    def chSubstr(first: Int, delta: Int = 0) = {
      s.substring(first, pos - 1 - delta)
    }

    // *** LEXER ***

    var tokenKind: TokenKind = BLANK
    var tokenValue: String = ""
    var linePos = 1
    var charPos = 1

    def getDigits() = {
      while (chKind == Digit) chNext()
    }

    def handleDigit() {
      val first = chMark
      getDigits()
      val k1 = if (ch == '.'.toInt) {
        chNext()
        getDigits()
        BIGNUMBER
      } else {
        NUMBER
      }
      val k2 = if (ch == 'E'.toInt || ch == 'e'.toInt) {
        chNext()
        if (ch == '+'.toInt) {
          chNext()
        } else if (ch == '-'.toInt) {
          chNext()
        }
        getDigits()
        FLOATNUMBER
      } else {
        k1
      }
      tokenKind = k2
      tokenValue = chSubstr(first)
    }

    def handleRaw() {
      chNext()
      val first = chMark
      var state = 0
      do {
        if (chKind == Eof) chError("EOF encountered in raw string")
        state = (ch, state) match {
          case ('}', _) => 1
          case ('"', 1) => 2
          case ('"', 2) => 3
          case ('"', 3) => 0
          case _ => 0
        }

        chNext()
      } while (state != 3)
      tokenKind = STRING
      tokenValue = chSubstr(first, 3)
    }

    def handle(i: Int) = {
      chNext()
      tokenKind = i
      tokenValue = ""
    }

    def tokenNext() {
      do {
        linePos = chLinePos
        charPos = chCharPos
        val kind: Int = chKind
        (kind: @switch) match {
          case Letter =>
            val first = chMark
            while (chKind == Letter || chKind == Digit) {
              chNext()
            }
            tokenKind = ID
            tokenValue = chSubstr(first)

          case Digit => handleDigit()
          case Minus =>
            chNext()
            handleDigit()
            tokenValue = "-" + tokenValue

          case Quote =>
            val sb = new StringBuilder(50)
            chNext()
            var first = chMark
            while (ch != '"'.toInt && ch >= 32) {
              if (ch == '\\'.toInt) {
                sb.append(chSubstr(first))
                chNext()
                escapeMap.get(ch) match {
                  case Some(s) =>
                    sb.append(s)
                    chNext()

                  case None =>
                    if (ch != 'u'.toInt) chError("Illegal escape")
                    chNext()
                    var code = 0
                    for (i <- 1 to 4) {
                      val ch1 = ch.toChar.toString
                      val i = "0123456789abcdef".indexOf(ch1.toLowerCase)
                      if (i == -1) chError("Illegal hex character")
                      code = code * 16 + i
                      chNext()
                    }
                    sb.append(code.toChar.toString)

                }
                first = chMark
              } else {
                chNext()
              }
            }
            if (ch != '"') chError("Unexpected string character:" + ch.toChar)

            sb.append(chSubstr(first))

            tokenKind = STRING
            tokenValue = sb.toString()
            chNext()
            if (tokenValue.length() == 0 && ch == '{') {
              handleRaw()
            }

          case Colon => handle(COLON)
          case Comma => handle(COMMA)
          case Lbra => handle(LOBJ)
          case Rbra => handle(ROBJ)
          case Larr => handle(LARR)
          case Rarr => handle(RARR)
          case Blank =>
            do chNext() while (chKind == Blank)
            tokenKind = BLANK
            tokenValue = ""

          case Other => chError("Unexpected character")
          case Eof =>
            chNext()
            tokenKind = EOF
            tokenValue = ""

          case Slash =>
            if (chKind != Slash) chError("Expecting Slash")
            do chNext() while (ch != '\n' && chKind != Eof)
            tokenKind = BLANK
            tokenValue = ""

        }
      } while (tokenKind == BLANK)
    }

    def tokenError(msg: String): Nothing = {
      throw new Json.Exception(msg, s, linePos, charPos)
    }

    // *** PARSER ***

    def handleEof() = tokenError("Unexpected eof")
    def handleUnexpected(i: String) = tokenError(s"Unexpected input: [$i]")

    def handleArray(): Js.Array = {
      tokenNext()
      var result = List.empty[Js.Value]
      while (tokenKind != RARR) {
        result = getJson() :: result
        (tokenKind: @switch) match{
          case COMMA => tokenNext()
          case RARR => // do nothing
          case _ => tokenError("Expecting , or ]")
        }
      }
      tokenNext()
      Js.Array(result.reverse)
    }

    def handleObject(): Js.Object = {
      tokenNext()
      var result = List.empty[(String, Js.Value)]

      while (tokenKind != ROBJ) {
        if (tokenKind != STRING && tokenKind != ID) tokenError("Expecting string or name")
        val name = tokenValue
        tokenNext()
        if (tokenKind != COLON) tokenError("Expecting :")
        tokenNext()
        result = (name -> getJson()) :: result
        (tokenKind: @switch) match{
          case COMMA => tokenNext()
          case ROBJ => // do nothing
          case _ => tokenError("Expecting , or }")
        }
      }
      tokenNext()
      Js.Object(result.reverse)
    }

    def getJson(): Js.Value = {
      val kind: Int = tokenKind
      val result: Js.Value = (kind: @switch) match {
        case ID =>
          val result = tokenValue match {
            case "true" => Js.True
            case "false" => Js.False
            case "null" => Js.Null
            case _ => tokenError("Not true, false, or null")
          }

          tokenNext()
          result

        case STRING =>
          val result = tokenValue
          tokenNext()
          Js.String(result)

        case NUMBER | BIGNUMBER | FLOATNUMBER =>
          val v = try {
            tokenValue.toLong
          } catch {
            case _: Throwable => tokenError("Bad integer")
          }
          tokenNext()
          val r = if (v >= Int.MinValue && v <= Int.MaxValue) v.toInt else v
          Js.Number("" + r)

        case COLON => handleUnexpected(":")
        case COMMA => handleUnexpected(",")
        case LOBJ => handleObject()
        case ROBJ => handleUnexpected("}")
        case LARR => handleArray()
        case RARR => handleUnexpected("]")
        case EOF => handleEof()
      }
      result
    }
    def parse(): Js.Value = {
      chNext()
      tokenNext()
      val result = getJson
      if (tokenKind != EOF) tokenError("Excess input")
      result
    }
    parse()
  }
  class Exception(val msg: String,
                  val input: String,
                  val line: Int,
                  val char: Int)
    extends scala.Exception(s"JsonParse Error: $msg line $line [$char] in $input")
}
