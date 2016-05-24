package dsentric

import cats.data.{Xor, _}

trait Data extends Any {

  def value:Any

  def render(implicit R:Renderer) =
    R.print(value)

  def asObject:Option[DObject] =
    value match {
      case m:Map[String, Any]@unchecked =>
        Some(new DObject(m))
      case _ =>
        None
    }

  def asArray:Option[DArray] =
    value match {
      case m:Vector[Any]@unchecked =>
        Some(new DArray(m))
      case _ =>
        None
    }
}

class DValue(val value:Any) extends AnyVal with Data

class DObject(val value:Map[String, Any]) extends AnyVal with Data{
  def +(v:(String, Data)) =
    new DObject(value + (v._1 -> v._2.value))

  def ++(v:TraversableOnce[(String, Data)]) =
    new DObject(value ++ v.map(t => t._1 -> t._2.value))

  def ++(m:DObject) =
    new DObject(value ++ m.value)

  def -(key:String) =
    new DObject(value - key)

  def --(keys:TraversableOnce[String]) =
    new DObject(value -- keys)

  def size = value.size

  def isEmpty = value.isEmpty

  def applyDelta(delta:DObject):DObject =
    DObjectOps.rightReduceConcat(this, delta)

  def reduce:Option[DObject] =
    DObjectOps.reduce(this)

}

class DQuery(val value:Map[String, Any]) extends AnyVal{


  def isMatch(j:DObject):Boolean =
    Query(Some(j.value), value)

  def &&(d:DQuery):DQuery =
    (value.get("$and"), d.value.get("$and")) match {
      case (None, None) =>
        if (value.contains("$or") || d.value.contains("$or"))
          new DQuery(Map("$and" -> Vector(value, d.value)))
        else
          new DQuery(DObjectOps.concatMap(value, d.value))
      case (None, Some(vr:Vector[Any]@unchecked)) =>
        new DQuery(Map("$and" -> (value +: vr)))
      case (Some(vl:Vector[Any]@unchecked), None) =>
        new DQuery(Map("$and" -> (vl :+ d.value)))
      case (Some(vl:Vector[Any]@unchecked), Some(vr:Vector[Any]@unchecked)) =>
        new DQuery(Map("$and" -> (vl ++ vr)))
      case _ =>
        new DQuery(Map("$and" -> Vector(value, d.value)))
    }


  def ||(d:DQuery):DQuery =
    (value.get("$or"), d.value.get("$or")) match {
      case (None, Some(vr:Vector[Any]@unchecked)) =>
        new DQuery(Map("$or" -> (value +: vr)))
      case (Some(vl:Vector[Any]@unchecked), None) =>
        new DQuery(Map("$or" -> (vl :+ d.value)))
      case (Some(vl:Vector[Any]@unchecked), Some(vr:Vector[Any]@unchecked)) =>
        new DQuery(Map("$or" -> (vl ++ vr)))
      case _ =>
        new DQuery(Map("$or" -> Vector(value, d.value)))
    }

  def ! :DQuery =
    new DQuery(Map("$not" -> value))

  def not:DQuery = this.!

  def toObject:DObject =
    new DObject(value)
}

class DProjection(val value:Map[String, Any]) extends AnyVal {

  def &(d:DProjection):DProjection =
    new DProjection(DObjectOps.concatMap(value, d.value))

  def toObject:DObject =
    new DObject(value)

  def select(obj:DObject):DObject =
    new DObject(DObjectOps.selectMap(obj.value, value))

}

class DArray(val value:Vector[Any]) extends AnyVal with Data {
  def toObjects = value.collect {
    case m:Map[String, Any]@unchecked =>
      new DObject(m)
  }
}

trait DNull

object Data{
  def apply[T](value:T)(implicit codec:DCodec[T]):Data =
    codec.apply(value)
}

object DObject{

  val empty = new DObject(Map.empty[String, Any])

  def apply(map:Map[String, Any]):DObject =
    new DObject(map)
  def apply(values:(String, Data)*):DObject =
    new DObject(values.toIterator.map(p => p._1 -> p._2.value).toMap)
}

object DArray{

  val empty = new DArray(Vector.empty)

  def apply[T](values:T*)(implicit codec:DCodec[T]) =
    new DArray(values.map(codec.apply(_).value).toVector)
}

object DQuery{

  //TODO confirm is valid query structure
  def apply(map:Map[String, Any]):NonEmptyList[(String, Path)] Xor DQuery =
    Xor.right(new DQuery(map))
  def apply(values:(String, Data)*):NonEmptyList[(String, Path)] Xor DQuery =
    Xor.right(new DQuery(values.toIterator.map(p => p._1 -> p._2.value).toMap))
}