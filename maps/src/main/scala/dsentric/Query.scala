package dsentric

import scala.util.matching.Regex
import queryTree._
import queryTree.Tree

trait PropertyExtension extends Any {
  def prop:Property[_]
  protected def nest(value:Any):DQuery =
    new DQuery(PathLensOps.pathToMap(prop._path, value))

}

trait Query {

  implicit def valueQuery[T](prop:Property[T]):ValueQuery[T] =
    new ValueQuery(prop)

  implicit def maybeQuery[T](prop:Maybe[T]):MaybeQuery[T] =
    new MaybeQuery(prop)

  implicit def numericQuery[T >: Numeric](prop:Property[T]):NumericQuery[T] =
    new NumericQuery(prop)

  implicit def stringQuery[T >: Optionable[String]](prop:Property[T]):StringQuery[T] =
    new StringQuery(prop)

  implicit class ArrayQuery[T](val prop: Expected[Vector[T]])(implicit codec: DCodec[T]) extends PropertyExtension  {

    def $elemMatch(f:Property[T] => DQuery):DQuery =
      nest(Map("$elemMatch" -> f(new EmptyProperty[T]).value))

    def $exists(isTrue:Boolean):DQuery =
      nest(Map("$exists" -> isTrue))
  }

  implicit class MaybeArrayQuery[T](val prop: Maybe[Vector[T]])(implicit codec: DCodec[T]) extends PropertyExtension  {

    def $elemMatch(f:Property[T] => DQuery):DQuery =
      nest(Map("$elemMatch" -> f(new EmptyProperty[T]).value))

    def $exists(isTrue:Boolean):DQuery =
      nest(Map("$exists" -> isTrue))
  }

  implicit class JArrayQuery(val prop: Expected[DArray]) extends PropertyExtension  {

    def $elemMatch(f:Property[Data] => DQuery):DQuery =
      nest(Map("$elemMatch" -> f(new EmptyProperty[Data]()(DefaultCodecs.dataCodec)).value))

    def $exists(isTrue:Boolean):DQuery =
      nest(Map("$exists" -> isTrue))

  }

  implicit class MaybeJArrayQuery(val prop: Maybe[DArray]) extends PropertyExtension  {

    def $elemMatch(f:Property[Data] => DQuery):DQuery =
      nest(Map("$elemMatch" -> f(new EmptyProperty[Data]()(DefaultCodecs.dataCodec)).value))

    def $exists(isTrue:Boolean):DQuery =
      nest(Map("$exists" -> isTrue))
  }

  implicit class ObjectArrayQuery[T <: Contract](val prop: ExpectedObjectArray[T]) extends PropertyExtension  {

    def $elemMatch(f:T => DQuery):DQuery =
      nest(Map("$elemMatch" -> f(prop.contract).value))

    def $exists(isTrue:Boolean):DQuery =
      nest(Map("$exists" -> isTrue))

  }

  implicit class MaybeObjectArrayQuery[T <: Contract](val prop: MaybeObjectArray[T]) extends PropertyExtension  {

    def $elemMatch(f:T => DQuery):DQuery =
      nest(Map("$elemMatch" -> f(prop.contract).value))

    def $exists(isTrue:Boolean):DQuery =
      nest(Map("$exists" -> isTrue))

  }

  implicit class DefaultObjectArrayQuery[T <: Contract](val prop: DefaultObjectArray[T]) extends PropertyExtension  {

    def $elemMatch(f:T => DQuery):DQuery =
      nest(Map("$elemMatch" -> f(prop.contract).value))

    def $exists(isTrue:Boolean):DQuery =
      nest(Map("$exists" -> isTrue))

  }

}

object Query extends Query {
  private[dsentric] def apply(value:Option[Any], query:Map[String, Any]):Boolean = {
    query.forall {
      case ("$and", values:Vector[Any]@unchecked) =>
        values.collect{ case m:Map[String, Any]@unchecked => m}.forall(apply(value, _))
      case ("$or", values:Vector[Any]@unchecked) =>
        values.collect{ case m:Map[String, Any]@unchecked => m}.exists(apply(value, _))
      case ("$eq", v) =>
        value.contains(v)
      case ("$ne", v) =>
        !value.contains(v)
      case ("$regex" | "$options", _) =>
        query.get("$regex").collect{
          case v:String =>
            val options = query.get("$options").collect{ case o:String => s"(?$o)" }.getOrElse("")
            value.collect { case s:String => (options + v).r.pattern.matcher(s).matches }.getOrElse(false)
        }.getOrElse(false)

      case ("$like", v:String) =>
        value.collect {
          case s:String =>
            ("(?i)" + v.replace("%", ".*")).r.pattern.matcher(s).matches
        }.getOrElse(false)
      case ("$lt", v) =>
        value.exists(x => order(x -> v).contains(-1))
      case ("$gt", v) =>
        value.exists(x => order(x -> v).contains(1))
      case ("$lte", v) =>
        value.exists(x => order(x -> v).exists(r => r <= 0))
      case ("$gte", v) =>
        value.exists(x => order(x -> v).exists(r => r >= 0))
      case ("$in", values:Vector[Any]@unchecked) =>
        value.exists(j => values.contains(j))
      case ("$nin", values:Vector[Any]@unchecked) =>
        !value.exists(j => values.contains(j)) //nin doesnt require existence, as per mongodb
      case ("$exists", v:Boolean) =>
        value.isDefined == v
      case ("$not", v:Map[String, Any]@unchecked) =>
        !apply(value, v)
      case ("$elemMatch", v:Map[String, Any]@unchecked) =>
        value.collect { case seq:Vector[Any]@unchecked => seq.exists(s => apply(Some(s), v)) }.getOrElse(false)
      case ("$elemMatch", v) =>
        value.collect { case seq:Vector[Any]@unchecked => seq.contains(v) }.getOrElse(false)
      case (key, v:Map[String, Any]@unchecked) =>
        apply(value.collect{ case m:Map[String, Any]@unchecked => m}.flatMap(_.get(key)), v)
      case (key, v) =>
        value.collect{ case m:Map[String, Any]@unchecked => m}.fold(false) { l =>
          l.get(key).contains(v)
        }
    }
  }

  private[dsentric] def apply(value:Any, query:Tree):Boolean = {
    import Dsentric._
    query match {
      case &(trees) =>
        trees.forall(t => this(value, t))
      case |(trees) =>
        trees.exists(t => this(value, t))
      case !!(tree) =>
        !this(value, tree)
      case ?(path, "$eq", v) =>
        search(value -> path).exists(x => order(x -> v).contains(0))
      case ?(path, "$ne", v) =>
        !search(value -> path).exists(x => order(x -> v).contains(0))
      case /(path, regex) =>
        search(value -> path).collect{case s:String => s}.exists(s => regex.pattern.matcher(s).matches)
      case ϵ(path, values) =>
        values.forall{ kv =>
          search(value -> (path \ kv._1)).exists(x => order(x -> kv._2).contains(0))
        }
      case %(path, _, regex) =>
        search(value -> path).collect{case s:String => s}.exists(s => regex.pattern.matcher(s).matches)
      case ?(path, "$lt", v) =>
        search(value -> path).exists(x => order(x -> v).contains(-1))
      case ?(path, "$gt", v) =>
        search(value -> path).exists(x => order(x -> v).contains(1))
      case ?(path, "$lte", v) =>
        search(value -> path).exists(x => order(x -> v).exists(r => r <= 0))
      case ?(path, "$gte", v) =>
        search(value -> path).exists(x => order(x -> v).exists(r => r >= 0))
      case ?(path, "$in", values:Vector[Any]@unchecked) =>
        search(value -> path).exists(j => values.contains(j))
      case ?(path, "$nin", values:Vector[Any]@unchecked) =>
        !search(value -> path).exists(j => values.contains(j))
      case ?(path, "$exists", v:Boolean) =>
        search(value -> path).nonEmpty == v
      case ∃(path, subQuery) =>
        search(value -> path).collect { case v:Vector[Any] => v.exists(s => apply(s, subQuery)) }.getOrElse(false)
      case _ =>
        false
    }
  }

  private def search:Function[(Any, Path), Option[Any]] = {
    case (v, Nil) =>
      Some(v)
    case (v:Map[String, Any]@unchecked, p@(_ :: _)) =>
      PathLensOps.traverse(v, p)
    case _ => None
  }

  private val order = DValueOps.order.lift

}

//Handle default?
class ValueQuery[T](val prop: Property[T]) extends AnyVal with PropertyExtension {

  def $eq(value:T) =
    if (prop.isInstanceOf[EmptyProperty[_]])
      new DQuery(Map("$eq" -> prop._codec(value).value))
    else
      nest(prop._codec(value).value)
  //Currently not supporting chaining of $ne in an && for the same field
  def $ne(value:T) = nest(Map("$ne" -> prop._codec(value).value))
  def $in(values:T*) = nest(Map("$in" -> values.map(prop._codec(_).value).toVector))
  def $nin(values:T*) = nest(Map("$nin" -> values.map(prop._codec(_).value).toVector))

}

class MaybeQuery[T](val prop:Maybe[T]) extends AnyVal with PropertyExtension {
  def $exists(value:Boolean) = nest(Map("$exists" -> value))
}

class NumericQuery[T >: Numeric](val prop: Property[T]) extends AnyVal with PropertyExtension {

  def $lt(value:Double) = nest(Map("$lt" -> value))
  def $lt(value:Long) = nest(Map("$lt" -> value))

  def $gt(value:Double) = nest(Map("$gt" -> value))
  def $gt(value:Long) = nest(Map("$gt" -> value))

  def $lte(value:Double) = nest(Map("$lt" -> value))
  def $lte(value:Long) = nest(Map("$lt" -> value))

  def $gte(value:Double) = nest(Map("$gt" -> value))
  def $gte(value:Long) = nest(Map("$gt" -> value))
}

class StringQuery[T >: Optionable[String]](val prop:Property[T]) extends AnyVal with PropertyExtension {

  def $regex(value:String) = nest(Map("$regex" -> value))
  def $regex(value:String, options:String) = nest(Map("$regex" -> value, "$options" -> options))
  def $regex(r:Regex) = nest(Map("$regex" -> r.regex))
  def $like(value:String) = nest(Map("$like" -> value))

}