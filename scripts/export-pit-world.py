"""Export the read-only 1.8.9 pit capture as an Anvil save and MCEdit schematic.

Retains original numeric block IDs, four-bit metadata, coordinates and tile NBT.
No game installation is modified. Run: py -3.11 scripts/export-pit-world.py
"""
import base64
import copy
import gzip
import io
import json
import struct
import time
import zipfile
import zlib
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT/'src/test/resources/dev/optimus/client/pit-capture'
OUT = ROOT/'exports'
NAME = 'Hypixel-Duels-Pit-2026-10-15'

def text(value):
    # Java DataOutput.writeUTF uses modified UTF-8, including surrogate pairs.
    out = bytearray()
    raw = value.encode('utf-16-be', 'surrogatepass')
    for offset in range(0, len(raw), 2):
        c = int.from_bytes(raw[offset:offset+2], 'big')
        if 0 < c < 128: out.append(c)
        elif c < 2048: out.extend((0xc0 | c >> 6, 0x80 | c & 63))
        else: out.extend((0xe0 | c >> 12, 0x80 | c >> 6 & 63, 0x80 | c & 63))
    return struct.pack('>H', len(out)) + out

def payload(kind, value):
    if kind in (1,2,3,4,5,6): return struct.pack({1:'>b',2:'>h',3:'>i',4:'>q',5:'>f',6:'>d'}[kind],value)
    if kind == 7: return struct.pack('>i',len(value)) + bytes(value)
    if kind == 8: return text(value)
    if kind == 9:
        child, values = value
        return bytes([child])+struct.pack('>i',len(values))+b''.join(payload(child,v) for v in values)
    if kind == 10: return b''.join(bytes([t])+text(n)+payload(t,v) for n,(t,v) in value.items())+b'\0'
    if kind in (11,12): return struct.pack('>i',len(value))+b''.join(struct.pack('>i' if kind==11 else '>q',v) for v in value)
    raise ValueError(kind)

def nbt(value, name=''):
    return b'\x0a'+text(name)+payload(10,value)

def read_nbt(raw):
    stream = io.BytesIO(raw)
    def take(fmt): return struct.unpack(fmt,stream.read(struct.calcsize(fmt)))[0]
    def string():
        data=stream.read(take('>H')).replace(b'\xc0\x80',b'\0')
        return data.decode('utf-8','surrogatepass').encode('utf-16','surrogatepass').decode('utf-16')
    def read(kind):
        if kind in (1,2,3,4,5,6): return take({1:'>b',2:'>h',3:'>i',4:'>q',5:'>f',6:'>d'}[kind])
        if kind == 7: return stream.read(take('>i'))
        if kind == 8: return string()
        if kind == 9:
            child,length=take('>b'),take('>i');return child,[read(child) for _ in range(length)]
        if kind == 10:
            result={}
            while (child:=take('>b')) != 0:
                name=string();result[name]=child,read(child)
            return result
        if kind in (11,12): return [take('>i' if kind==11 else '>q') for _ in range(take('>i'))]
        raise ValueError(kind)
    assert take('>b')==10
    string()
    result=read(10)
    assert stream.read()==b''
    return result

def nibble(array, index, value):
    shift=(index & 1)*4
    array[index//2]=(array[index//2] & ~(15 << shift)) | ((value & 15) << shift)

def main():
    context=(SOURCE/'capture-context.txt').read_text(encoding='utf-8')
    assert 'complete=true' in context and 'sameWorld=true' in context and 'missingColumns=0' in context
    blocks={}
    with gzip.open(SOURCE/'pit-blocks.tsv.gz','rt') as stream:
        for line in stream:
            p=line.split()
            if p[0]=='S':
                x,y,z,bid,meta=map(int,p[1:]);assert 0<bid<4096 and 0<=meta<16
                blocks[x,y,z]=(bid,meta)
    tiles=[]
    with gzip.open(SOURCE/'pit-tiles.tsv.gz','rt') as stream:
        for line in stream:
            p=line.split()
            if p[0]=='T': tiles.append(read_nbt(gzip.decompress(base64.b64decode(p[4]))))
    expected_tiles=sum(bid in (63,68) for bid,meta in blocks.values())
    assert len(tiles)==expected_tiles,(len(tiles),expected_tiles)
    minimum=[min(p[i] for p in blocks) for i in range(3)]
    maximum=[max(p[i] for p in blocks) for i in range(3)]
    width,height,length=[maximum[i]-minimum[i]+1 for i in range(3)]
    ids=bytearray(width*height*length);metadata=bytearray(len(ids));extra=bytearray((len(ids)+1)//2)
    for (x,y,z),(bid,meta) in blocks.items():
        index=((y-minimum[1])*length+z-minimum[2])*width+x-minimum[0]
        ids[index]=bid & 255;metadata[index]=meta;nibble(extra,index,bid >> 8)
    relative_tiles=copy.deepcopy(tiles)
    for tile in relative_tiles:
        for axis,offset in zip(('x','y','z'),minimum): tile[axis]=(3,tile[axis][1]-offset)
    schematic={'Materials':(8,'Alpha'),'Width':(2,width),'Height':(2,height),'Length':(2,length),
               'Blocks':(7,ids),'Data':(7,metadata),'Entities':(9,(10,[])),
               'TileEntities':(9,(10,relative_tiles)),**{f'WEOrigin{axis}':(3,n) for axis,n in zip('XYZ',minimum)},
               **{f'WEOffset{axis}':(3,0) for axis in 'XYZ'}}
    if any(extra): schematic['AddBlocks']=(7,extra)
    OUT.mkdir(exist_ok=True)
    schematic_path=OUT/(NAME+'.schematic')
    schematic_path.write_bytes(gzip.compress(nbt(schematic,'Schematic'),mtime=0))
    world=ROOT/'build/example-world'/NAME
    (world/'region').mkdir(parents=True,exist_ok=True)
    player={'Pos':(9,(6,[.5,80.,-102.5])),'Motion':(9,(6,[0.,0.,0.])),
            'Rotation':(9,(5,[180.,35.])),'Dimension':(3,0),'Health':(5,20.),'foodLevel':(3,20),
            'Inventory':(9,(10,[])),'EnderItems':(9,(10,[])),'playerGameType':(3,1),
            'abilities':(10,{'invulnerable':(1,1),'flying':(1,1),'mayfly':(1,1),
                             'instabuild':(1,1),'mayBuild':(1,1),'flySpeed':(5,.05),'walkSpeed':(5,.1)})}
    data={'version':(3,19133),'LevelName':(8,'Hypixel Duels pit · 15 October 2026'),
          'RandomSeed':(4,0),'generatorName':(8,'flat'),'generatorVersion':(3,0),
          'generatorOptions':(8,'3;minecraft:air;1;'),'MapFeatures':(1,0),'GameType':(3,1),
          'SpawnX':(3,0),'SpawnY':(3,80),'SpawnZ':(3,-103),'Time':(4,6000),'DayTime':(4,6000),
          'LastPlayed':(4,int(time.time()*1000)),'SizeOnDisk':(4,0),'allowCommands':(1,1),
          'hardcore':(1,0),'initialized':(1,1),'Difficulty':(1,0),'DifficultyLocked':(1,1),
          'raining':(1,0),'thundering':(1,0),'BorderCenterX':(6,0.),'BorderCenterZ':(6,-102.),
          'BorderSize':(6,256.),'GameRules':(10,{'doMobSpawning':(8,'false'),'doDaylightCycle':(8,'false'),
              'keepInventory':(8,'true'),'doFireTick':(8,'false'),'randomTickSpeed':(8,'0')}),'Player':(10,player)}
    (world/'level.dat').write_bytes(gzip.compress(nbt({'Data':(10,data)}),mtime=0))
    chunks=defaultdict(dict)
    for pos,value in blocks.items(): chunks[pos[0]//16,pos[2]//16][pos]=value
    regions=defaultdict(dict)
    for (cx,cz),cells in chunks.items():
        sections={};heights=[0]*256
        for (x,y,z),(bid,meta) in cells.items():
            section=sections.setdefault(y//16,[bytearray(4096),bytearray(2048),bytearray(2048)])
            index=(y & 15)*256+(z & 15)*16+(x & 15)
            section[0][index]=bid & 255;nibble(section[1],index,meta);nibble(section[2],index,bid >> 8)
            column=(z & 15)*16+(x & 15);heights[column]=max(heights[column],y+1)
        section_list=[]
        for sy,(section_ids,section_meta,section_extra) in sorted(sections.items()):
            section={'Y':(1,sy),'Blocks':(7,section_ids),'Data':(7,section_meta),
                     'BlockLight':(7,bytes(2048)),'SkyLight':(7,b'\xff'*2048)}
            if any(section_extra): section['Add']=(7,section_extra)
            section_list.append(section)
        chunk_tiles=[t for t in tiles if t['x'][1]//16==cx and t['z'][1]//16==cz]
        level={'xPos':(3,cx),'zPos':(3,cz),'LastUpdate':(4,0),'InhabitedTime':(4,0),
               'TerrainPopulated':(1,1),'LightPopulated':(1,0),'Biomes':(7,b'\x01'*256),
               'HeightMap':(11,heights),'Sections':(9,(10,section_list)),
               'Entities':(9,(10,[])),'TileEntities':(9,(10,chunk_tiles)),'TileTicks':(9,(10,[]))}
        regions[cx//32,cz//32][cx & 31,cz & 31]=nbt({'Level':(10,level)})
    for (rx,rz),region in regions.items():
        header=bytearray(8192);body=bytearray();sector=2
        for (cx,cz),raw in sorted(region.items()):
            compressed=zlib.compress(raw);record=struct.pack('>I',len(compressed)+1)+b'\x02'+compressed
            count=(len(record)+4095)//4096;slot=cx+cz*32
            header[slot*4:slot*4+4]=((sector << 8)|count).to_bytes(4,'big')
            header[4096+slot*4:4100+slot*4]=int(time.time()).to_bytes(4,'big')
            body.extend(record+b'\0'*(count*4096-len(record)));sector+=count
        (world/'region'/f'r.{rx}.{rz}.mca').write_bytes(header+body)
    manifest=dict(captured_utc=context.split('capturedUtc=')[1].splitlines()[0],server='Hypixel Duels lobby 1',
                  world_bounds=dict(minimum=minimum,maximum=maximum),pit_bounds=[[-25,62.5,-127],[25,78,-77]],
                  blocks=len(blocks),tile_entities=len(tiles),chunks=len(chunks),minecraft_version='1.8.9')
    (world/'PIT_CAPTURE.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    readme=('Hypixel Duels pit, captured 15 October 2026\n\n'
            'Open with Minecraft Java 1.8.9 (vanilla or Forge). Extract this folder into your saves folder.\n'
            'The save starts in Creative flight above the pit. Original world coordinates are retained.\n'
            'Only the captured pit plus a two-block horizontal margin is included. Outside is void.\n'
            'Block IDs, metadata and existing sign text are preserved. Use your usual resource pack for its appearance.\n'
            'Players, NPCs, server plugins, PvP and server-specific fall-damage behavior are not part of a world save.\n'
            'Creative mode allows inspection without fall damage.\n')
    (world/'README.txt').write_text(readme,encoding='utf-8')
    archive=OUT/(NAME+'.zip')
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as out:
        for file in sorted(world.rglob('*')):
            if file.is_file(): out.write(file,Path(NAME)/file.relative_to(world))
    # Round-trip both exported formats against every original block, metadata and tile.
    check=read_nbt(gzip.decompress(schematic_path.read_bytes()))
    assert len(check['TileEntities'][1][1])==len(tiles)
    for (x,y,z),(bid,meta) in blocks.items():
        index=((y-minimum[1])*length+z-minimum[2])*width+x-minimum[0]
        assert check['Blocks'][1][index]==bid and check['Data'][1][index]==meta
    restored={}
    for region in (world/'region').glob('*.mca'):
        raw=region.read_bytes()
        for slot in range(1024):
            offset=int.from_bytes(raw[slot*4:slot*4+4],'big') >> 8
            if not offset: continue
            cursor=offset*4096;size=int.from_bytes(raw[cursor:cursor+4],'big');assert raw[cursor+4]==2
            chunk=read_nbt(zlib.decompress(raw[cursor+5:cursor+4+size]))['Level'][1]
            cx,cz=chunk['xPos'][1],chunk['zPos'][1]
            for s in chunk['Sections'][1][1]:
                sy=s['Y'][1]
                for index,bid in enumerate(s['Blocks'][1]):
                    if not bid: continue
                    x=cx*16+(index & 15);z=cz*16+(index >> 4 & 15);y=sy*16+(index >> 8)
                    m=s['Data'][1][index//2] >> ((index & 1)*4) & 15
                    restored[x,y,z]=bid,m
    assert restored==blocks,'Anvil round-trip changed blocks or metadata'
    assert read_nbt(gzip.decompress((world/'level.dat').read_bytes()))['Data'][1]['version'][1]==19133
    print(json.dumps(manifest,indent=2))
    print(f'PASS: all {len(blocks)} blocks and metadata round-trip in schematic and Anvil save; {len(tiles)} tile entities retained')
    print(archive)
    print(schematic_path)

if __name__=='__main__': main()