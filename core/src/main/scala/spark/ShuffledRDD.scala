package spark

import scala.collection.mutable.HashMap


class ShuffledRDDSplit(val idx: Int) extends Split {
  override val index = idx
  override def hashCode(): Int = idx
}

class ShuffledRDD[K, V, C](
  parent: RDD[(K, V)],
  aggregator: Aggregator[K, V, C],
  part : Partitioner)
extends RDD[(K, C)](parent.context) {
  //override val partitioner = Some(part)
  override val partitioner = Some(part)
  
  @transient private var splits_ =
    Array.tabulate[Split](part.numPartitions)(i => new ShuffledRDDSplit(i))

  override def splits = splits_
  
  override def preferredLocations(split: Split) = Nil
  
  val dep = new ShuffleDependency(context.newShuffleId, parent, aggregator, part)
  override val dependencies = List(dep)

  override def compute(split: Split): Iterator[(K, C)] = {
    val combiners = new HashMap[K, C]
    def mergePair(k: K, c: C) {
      combiners(k) = combiners.get(k) match {
        case Some(oldC) => aggregator.mergeCombiners(oldC, c)
        case None => c
      }
    }
    val fetcher = SparkEnv.get.shuffleFetcher
    fetcher.fetch[K, C](dep.shuffleId, split.index, mergePair)
    combiners.iterator
  }

  private def writeObject(stream: java.io.ObjectOutputStream) {
    stream.defaultWriteObject()
    stream match {
      case _: EventLogOutputStream =>
        stream.writeObject(splits_)
      case _ => {}
    }
  }

  private def readObject(stream: java.io.ObjectInputStream) {
    stream.defaultReadObject()
    stream match {
      case s: EventLogInputStream =>
        splits_ = s.readObject().asInstanceOf[Array[Split]]
      case _ => {}
    }
  }

  reportCreation()
}
