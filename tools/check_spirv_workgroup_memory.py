"""Measure scalar/vector/array Workgroup storage in a compiled SPIR-V shader."""
from __future__ import annotations

import argparse
import json
import struct
from pathlib import Path


def workgroup_memory_bytes(path: Path) -> int:
    data = path.read_bytes()
    if len(data) < 20 or len(data) % 4:
        raise ValueError('SPIR-V header/payload is truncated')
    words = struct.unpack('<'+'I'*(len(data)//4), data)
    if words[0] != 0x07230203:
        raise ValueError('unsupported SPIR-V magic/byte order')
    constants = {}
    types = {}
    workgroup_pointer_types = []
    position = 5
    while position < len(words):
        instruction = words[position]
        count, opcode = instruction >> 16, instruction & 0xFFFF
        if count == 0 or position+count > len(words):
            raise ValueError('invalid SPIR-V instruction extent')
        operands = words[position+1:position+count]
        if opcode in (21, 22, 23, 28, 30, 32):  # Int/Float/Vector/Array/Struct/Pointer
            types[operands[0]] = (opcode, operands[1:])
        elif opcode == 43:  # OpConstant; array lengths in these shaders are uint32
            constants[operands[1]] = operands[2]
        elif opcode == 59 and operands[2] == 4:  # OpVariable, StorageClass Workgroup
            workgroup_pointer_types.append(operands[0])
        position += count

    def size(type_id):
        opcode, operands = types[type_id]
        if opcode in (21, 22):
            return operands[0]//8
        if opcode == 23:
            if operands[1] == 3:
                raise ValueError('vec3 Workgroup padding requires target-specific reflection')
            return size(operands[0])*operands[1]
        if opcode == 28:
            return size(operands[0])*constants[operands[1]]
        if opcode == 30:
            raise ValueError('struct Workgroup alignment requires target-specific reflection')
        if opcode == 32:
            return size(operands[1])
        raise ValueError(f'unsupported shared type opcode {opcode}')

    # Shared arrays use scalar floats in the ISP, so no vector/struct padding is
    # hidden here. This intentionally refuses unsupported SPIR-V types.
    return sum(size(type_id) for type_id in workgroup_pointer_types)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('shader', type=Path)
    parser.add_argument('--limit-bytes', type=int, default=32768)
    args = parser.parse_args()
    if args.limit_bytes <= 0:
        parser.error('--limit-bytes must be positive')
    amount = workgroup_memory_bytes(args.shader)
    print(json.dumps({'shader': str(args.shader), 'workgroup_memory_bytes': amount,
                      'limit_bytes': args.limit_bytes, 'passed': amount <= args.limit_bytes}, indent=2))
    raise SystemExit(0 if amount <= args.limit_bytes else 1)


if __name__ == '__main__':
    main()
