/* Bounded reader for big-endian Java-edition NBT. No imported content is executed. */
(function (root) {
  'use strict';
  function read(bytes) {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    let offset = 0,
      tags = 0;
    function take(count) {
      if (
        !Number.isSafeInteger(count) ||
        count < 0 ||
        count > bytes.length - offset
      )
        throw new Error('Truncated or oversized NBT data.');
      const start = offset;
      offset += count;
      return start;
    }
    const byte = () => view.getUint8(take(1));
    const short = () => view.getInt16(take(2), false);
    const int = () => view.getInt32(take(4), false);
    function count() {
      const value = int();
      if (value < 0 || value > 8_000_000)
        throw new Error('NBT collection is too large.');
      return value;
    }
    function string() {
      const length = view.getUint16(take(2), false),
        end = offset + length;
      take(length);
      let result = '';
      // Java writes modified UTF-8, including surrogate code units and encoded NUL.
      for (let i = end - length; i < end; ) {
        const a = bytes[i++];
        if (a < 128) result += String.fromCharCode(a);
        else if ((a & 224) === 192 && i < end) {
          const b = bytes[i++];
          if ((b & 192) !== 128) throw new Error('Invalid NBT string.');
          result += String.fromCharCode(((a & 31) << 6) | (b & 63));
        } else if ((a & 240) === 224 && i + 1 < end) {
          const b = bytes[i++],
            c = bytes[i++];
          if ((b & 192) !== 128 || (c & 192) !== 128)
            throw new Error('Invalid NBT string.');
          result += String.fromCharCode(
            ((a & 15) << 12) | ((b & 63) << 6) | (c & 63)
          );
        } else throw new Error('Unsupported NBT string encoding.');
      }
      return result;
    }
    function payload(type, depth) {
      if (depth > 64 || ++tags > 1_000_000)
        throw new Error('NBT nesting or tag count exceeds the import limit.');
      switch (type) {
        case 1:
          return view.getInt8(take(1));
        case 2:
          return short();
        case 3:
          return int();
        case 4: {
          const high = int(),
            low = view.getUint32(take(4), false);
          return high * 4294967296 + low;
        }
        case 5:
          return view.getFloat32(take(4), false);
        case 6:
          return view.getFloat64(take(8), false);
        case 7: {
          const length = count();
          return bytes.subarray(take(length), offset);
        }
        case 8:
          return string();
        case 9: {
          const itemType = byte(),
            length = count();
          if (length && itemType === 0)
            throw new Error('Invalid NBT list type.');
          const result = [];
          for (let i = 0; i < length; i++)
            result.push(payload(itemType, depth + 1));
          return result;
        }
        case 10: {
          const result = Object.create(null);
          for (let next; (next = byte()) !== 0; ) {
            const name = string();
            if (Object.prototype.hasOwnProperty.call(result, name))
              throw new Error('Duplicate NBT field.');
            result[name] = payload(next, depth + 1);
          }
          return result;
        }
        case 11: {
          const result = new Int32Array(count());
          for (let i = 0; i < result.length; i++) result[i] = int();
          return result;
        }
        case 12: {
          const length = count();
          take(length * 8);
          return null;
        }
        default:
          throw new Error('Unknown NBT tag type: ' + type);
      }
    }
    if (byte() !== 10) throw new Error('Expected an NBT compound root.');
    const name = string(),
      value = payload(10, 0);
    return { name, value };
  }
  const api = { read };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.MapNbt = api;
})(typeof window === 'undefined' ? globalThis : window);