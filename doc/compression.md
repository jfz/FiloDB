# Compression

Here we will discuss some of the compression algorithms in FiloDB's underlying chunk format.

In general, the overall principle is to use knowlewdge of the underlying data to reduce the number of bits and bytes that need to be stored.  Some specific examples follow.

## Long/Integer Compression

For the most part "Delta delta" compression is used.  Delta delta models all the values in a vector as a sloped line, which is pretty representative of a large amount of time series and operational data (think: increasing counters, flat lines).  The starting point and slope of the line is measured.  Then, the delta of each data point away from the line is actually what is saved.  This is typically much smaller than the raw bit width of the incoming data.

## Floating Point Compression

1. Gorilla XOR - simple XOR of previous value, works pretty well when values don't change much
2. [FPC/DFCM/FCM](https://userweb.cs.txstate.edu/~burtscher/papers/tr06.pdf) - using two predictors, one linear and one differential, for better predictive ability
3. Experiment with delta delta for floats?

These all have the capability to redue the number of bits in successive values.  Imagine that all of these algorithms produce a stream of 64-bit values with many more zeroes.  Now how do we encode them?

## Predictive NibblePacking

This is a storage scheme which takes the output of one of the compression algorithms (really, they are **prediction** algorithms) above, which is a stream of 64-bit numbers with hopefully many zero bits, and encodes them in an efficient way.  This encoding scheme works for the output of any of the integer or floating point compression/prediction schemes.  We make these observations:

1. The 64-bit values may have both leading zeros and trailing zeros (def true for floating point XOR/DFCM output)
2. Most sequences of these values have very similar numbers of leading and trailing zeros.  
3. It is faster to process and encode multiple values at once
4. It may be nice to be able to skip over a whole bunch of values at once during decoding

The predictive nibblepacking scheme encodes 8 64-bit values at once, storing the min number of leading and trailing zeros one time plus a bitmask of non-zero values.  Here is the storage scheme:

| offset | description |
| ------ | ----------- |
| +0     | u8: bitmask, 1=nonzero value, 0=zero value  |
| +1     | u8: bits 0-3 = number of trailing zero nibbles (0-15); 4-7 = number of leading zero nibbles (0-15); skipped if bitmask == 0  |
| +2     | little-endian nibble storage for each nonzero value in the bitmask; each value has (16 - leading - trailing) nibbles.  Skipped if bitmask = 0 |

The total space required to encode the 8 values can be derived as follows:

```scala
    if (bitmask == 0) {
        1
    } else {
        numNibbles = 16 - leadingZeroNibbles - trailingZeroNibbles
        2 + (numNibbles * bitcount(bitmask) + 1)/2
    }
```

Encoding involves these steps:
1. Determine the min of the leading # of zeros of all 8 values
2. Determine the min of the trailing # of zeros of all 8 values
3. For each value:
    - Update bitmask if nonzero
    - >> (trailingZeroNibbles * 4)
    - Store numNibbles, LSB first, into nibble storage area

Decoding is the reverse of the above steps.

### Example

Imagine that each value requires 3 nibbles for storage, and the values to be stored are:
    0x0000_0000_0012_3000
    0x0000_0000_0045_6000

So let's go through the steps:
1. The min # of leading zeroes is 41, or 10 nibbles
2. The min # of trailing zeroes is 12, or 3 nibbles

Next we process each value.  3 nibbles is needed to store the values.  Each one will be shifted to the right:

    0x0000_0123
    0x0000_0456

Now here is how they would be stored in memory:

    23 61 45

Or, if the above was viewed in a little-endian system as a 32-bit int, then the above would be 0x00456123.



