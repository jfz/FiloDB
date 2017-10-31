package filodb.memory

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

import scala.collection.JavaConverters._

import filodb.memory.format.BinaryVector.{HeaderMagic, Memory}
import filodb.memory.format.{BinaryVector, UnsafeUtils}
import filodb.memory.format.UnsafeUtils.arayOffset

import com.kenai.jffi.MemoryIO

/**
  * A trait which allows allocation of memory with the Filo magic header
  */
trait MemFactory {

  /**
    * Allocates memory for requested size plus 4 bytes for magic header
    *
    * @param size Request memory allocation size in bytes
    * @return Memory which has a base, offset and a length
    */
  def allocateWithMagicHeader(size: Int): Memory

  /**
    * Frees memory allocated at the passed address
    *
    * @param address The native address which represents the starting location of memory allocated
    */
  def freeMemory(address: Long): Unit

  def fromBuffer(buf: ByteBuffer): Memory = {
    if (buf.hasArray) {
      (buf.array, arayOffset.toLong + buf.arrayOffset + buf.position, buf.limit - buf.position)
    } else {
      assert(buf.isDirect)
      val address = MemoryIO.getCheckedInstance.getDirectBufferAddress(buf)
      (UnsafeUtils.ZeroPointer, address + buf.position, buf.limit - buf.position)
    }
  }
}

object MemFactory {
  val onHeapFactory = new ArrayBackedMemFactory()
}

/**
  * Uses MemoryIO to do Dynamic memory allocation
  */
class NativeMemoryManager(val upperBoundSizeInBytes: Long) extends MemFactory with CleanShutdown {

  protected val usedSoFar = new AtomicLong(0)
  protected val sizeMapping = new ConcurrentHashMap[Long, Long]()

  def usedMemory: Long = usedSoFar.get()

  def availableDynMemory: Long = upperBoundSizeInBytes - usedSoFar.get()


  override def allocateWithMagicHeader(allocateSize: Int): Memory = {
    val currentSize = usedSoFar.get()
    //4 for magic header
    val size = allocateSize + 4
    val resultantSize = currentSize + size
    if (!(resultantSize > upperBoundSizeInBytes)) {
      val address: Long = MemoryIO.getCheckedInstance().allocateMemory(size, true)
      usedSoFar.compareAndSet(currentSize, currentSize + size)
      sizeMapping.put(address, size)
      UnsafeUtils.setInt(UnsafeUtils.ZeroPointer, address, HeaderMagic)
      (UnsafeUtils.ZeroPointer, address + 4, allocateSize)
    } else {
      val msg = s"Resultant memory size $resultantSize after allocating " +
        s"with requested size $size is greater than upper bound size $upperBoundSizeInBytes"
      throw new IndexOutOfBoundsException(msg)
    }
  }


  override def freeMemory(startAddress: Long): Unit = {
    //start where the header started
    val address = startAddress - 4
    val size = sizeMapping.get(address)
    if (size > 0) {
      val currentSize = usedSoFar.get()
      MemoryIO.getCheckedInstance().freeMemory(address)
      usedSoFar.compareAndSet(currentSize, currentSize - size)
      val removed = sizeMapping.remove(address)
    } else {
      val msg = s"Address $address was not allocated by this memory manager"
      throw new IllegalArgumentException(msg)
    }
  }

  protected[memory] def freeAll(): Unit = {
    sizeMapping.entrySet().asScala.foreach(entry => if (entry.getValue > 0) {
      MemoryIO.getCheckedInstance().freeMemory(entry.getKey)
    })
    sizeMapping.clear()
  }

  override def shutdown(): Unit = {
    freeAll()
  }

}

class ArrayBackedMemFactory extends MemFactory {
  /**
    * Allocates memory for requested size.
    *
    * @param size Request memory allocation size in bytes
    * @return Memory which has a base, offset and a length
    */
  override def allocateWithMagicHeader(size: Int): (Any, Long, Int) = {
    val newBytes = new Array[Byte](size + 4)
    UnsafeUtils.setInt(newBytes, UnsafeUtils.arayOffset, BinaryVector.HeaderMagic)
    (newBytes, UnsafeUtils.arayOffset + 4, size)
  }

  /**
    * Frees memory allocated at the passed address
    *
    * @param address The native address which represents the starting location of memory allocated
    */
  override def freeMemory(address: Long): Unit = {} //do nothing
}


/**
  * A holder which maintains a reference to a currentBlock which is replaced when
  * it is full
  *
  * @param blockStore The BlockStore which is used to request more blocks when the current
  *                   block is full.
  */
class BlockHolder(blockStore: BlockManager, canReclaim: (Block) => Boolean) extends MemFactory {

  val currentBlock = new AtomicReference[Block]()

  currentBlock.set(blockStore.requestBlock(canReclaim).get)

  protected def ensureCapacity(forSize: Long): Block = {
    if (!currentBlock.get().hasCapacity(forSize)) {
      currentBlock.set(blockStore.requestBlock(canReclaim).get)
    }
    currentBlock.get()
  }

  /**
    * Allocates memory for requested size.
    *
    * @param size Request memory allocation size in bytes
    * @return Memory which has a base, offset and a length
    */
  override def allocateWithMagicHeader(allocateSize: Int): Memory = {
    //4 for magic header
    val size = allocateSize + 4
    val block = ensureCapacity(size)
    block.own()
    val preAllocationPosition = block.position()
    val headerAddress = block.address + preAllocationPosition
    UnsafeUtils.setInt(UnsafeUtils.ZeroPointer, headerAddress, BinaryVector.HeaderMagic)
    block.position(preAllocationPosition + 4 + allocateSize)
    (UnsafeUtils.ZeroPointer, headerAddress + 4, allocateSize)
  }

  /**
    * Frees memory allocated at the passed address
    *
    * @param address The native address which represents the starting location of memory allocated
    */
  override def freeMemory(address: Long): Unit = {
    throw new UnsupportedOperationException
  }
}

