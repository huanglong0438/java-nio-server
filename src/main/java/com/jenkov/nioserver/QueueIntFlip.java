package com.jenkov.nioserver;

/**
 * Same as QueueFillCount, except that QueueFlip uses a flip flag to keep track of when the internal writePos has
 * "overflowed" (meaning it goes back to 0). Other than that, the two implementations are very similar in functionality.
 *
 * One additional difference is that QueueFlip has an available() method, where this is a public variable in
 * QueueFillCount.
 *
 * Created by jjenkov on 18-09-2015.
 */
public class QueueIntFlip {

    // 每个一格element是一个section
    public int[] elements = null;

    public int capacity = 0;
    public int writePos = 0;
    public int readPos  = 0;
    public boolean flipped = false;

    public QueueIntFlip(int capacity) {
        this.capacity = capacity;
        this.elements = new int[capacity]; //todo get from TypeAllocator ?
    }

    public void reset() {
        this.writePos = 0;
        this.readPos  = 0;
        this.flipped  = false;
    }

    public int available() {
        if(!flipped){
            return writePos - readPos;
        }
        return capacity - readPos + writePos;
    }

    public int remainingCapacity() {
        if(!flipped){
            return capacity - writePos;
        }
        return readPos - writePos;
    }

    public boolean put(int element){
        if(!flipped){
            if(writePos == capacity){
                // 等于capacity说明超了，就flip（开启覆盖模式）
                writePos = 0;
                flipped = true;

                if(writePos < readPos){
                    // 走到这里说明读了，所以那些读过的位置就可以覆盖了，这个flip就是这个意思
                    elements[writePos++] = element;
                    return true;
                } else {
                    return false;
                }
            } else {
                // 没超过capacity就规规矩矩的往elements里塞 0-4K 1-8K ... n-4n K
                elements[writePos++] = element;
                return true;
            }
        } else {
            // 覆盖模式
            if(writePos < readPos ){
                elements[writePos++] = element;
                return true;
            } else {
                return false;
            }
        }
    }

    public int put(int[] newElements, int length){
        int newElementsReadPos = 0;
        if(!flipped){
            //readPos lower than writePos - free sections are:
            //1) from writePos to capacity
            //2) from 0 to readPos

            if(length <= capacity - writePos){
                //new elements fit into top of elements array - copy directly
                for(; newElementsReadPos < length; newElementsReadPos++){
                    this.elements[this.writePos++] = newElements[newElementsReadPos];
                }

                return newElementsReadPos;
            } else {
                //new elements must be divided between top and bottom of elements array

                //writing to top
                for(;this.writePos < capacity; this.writePos++){
                    this.elements[this.writePos] = newElements[newElementsReadPos++];
                }

                //writing to bottom
                this.writePos = 0;
                this.flipped  = true;
                int endPos = Math.min(this.readPos, length - newElementsReadPos);
                for(; this.writePos < endPos; this.writePos++){
                    this.elements[writePos] = newElements[newElementsReadPos++];
                }


                return newElementsReadPos;
            }

        } else {
            //readPos higher than writePos - free sections are:
            //1) from writePos to readPos

            int endPos = Math.min(this.readPos, this.writePos + length);

            for(; this.writePos < endPos; this.writePos++){
                this.elements[this.writePos] = newElements[newElementsReadPos++];
            }

            return newElementsReadPos;
        }
    }


    /**
     * 和put相反的是，take是从 0-4K 1-8K ... n-4n K 中取出数据
     *
     * @return
     */
    public int take() {
        if(!flipped){
            // 不是覆盖模式的时候，说明写位置在读位置后面，规规矩矩的读
            if(readPos < writePos){
                return elements[readPos++];
            } else {
                return -1;
            }
        } else {
            // 覆盖模式的时候，写位置在读位置前面
            if(readPos == capacity){
                // 读过界了，就继续读到写操作的位置
                readPos = 0;
                flipped = false;

                if(readPos < writePos){
                    return elements[readPos++];
                } else {
                    return -1;
                }
            } else {
                // 读还没过界，那就规规矩矩的读
                return elements[readPos++];
            }
        }
    }

    public int take(int[] into, int length){
        int intoWritePos = 0;
        if(!flipped){
            //writePos higher than readPos - available section is writePos - readPos

            int endPos = Math.min(this.writePos, this.readPos + length);
            for(; this.readPos < endPos; this.readPos++){
                into[intoWritePos++] = this.elements[this.readPos];
            }
            return intoWritePos;
        } else {
            //readPos higher than writePos - available sections are top + bottom of elements array

            if(length <= capacity - readPos){
                //length is lower than the elements available at the top of the elements array - copy directly
                for(; intoWritePos < length; intoWritePos++){
                    into[intoWritePos] = this.elements[this.readPos++];
                }

                return intoWritePos;
            } else {
                //length is higher than elements available at the top of the elements array
                //split copy into a copy from both top and bottom of elements array.

                //copy from top
                for(; this.readPos < capacity; this.readPos++){
                    into[intoWritePos++] = this.elements[this.readPos];
                }

                //copy from bottom
                this.readPos = 0;
                this.flipped = false;
                int endPos = Math.min(this.writePos, length - intoWritePos);
                for(; this.readPos < endPos; this.readPos++){
                    into[intoWritePos++] = this.elements[this.readPos];
                }

                return intoWritePos;
            }
        }
    }

}
