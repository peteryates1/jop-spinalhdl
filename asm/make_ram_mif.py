#!/usr/bin/env python3
"""Convert mem_ram.dat (decimal, one word per line) to Altera MIF format."""
import sys

def main():
    in_path = sys.argv[1]
    out_path = sys.argv[2]
    depth = int(sys.argv[3]) if len(sys.argv) > 3 else 256
    width = 32

    with open(in_path) as f:
        vals = [int(line.strip()) & 0xFFFFFFFF for line in f if line.strip()]

    with open(out_path, 'w') as f:
        f.write(f'--\n--  ram.mif\n--\n')
        f.write(f'depth = {depth};\nwidth = {width};\n\ncontent\n\nbegin\n')
        for i, v in enumerate(vals):
            f.write(f'  {i:03x} : {v:08x};\n')
        if len(vals) < depth:
            f.write(f'  [{len(vals):03x}..{depth-1:03x}] : 00000000;\n')
        f.write('end;\n')

if __name__ == '__main__':
    main()
