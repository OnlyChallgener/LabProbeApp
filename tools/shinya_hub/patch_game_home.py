"""Rearrange only the requested game home entries using existing RN primitives."""
import struct
import ctypes
from hermes_dec.parsers.hbc_opcodes import hbc90 as ops

# Reuse captions owned by erased game feedback / game tips implementations.
STRINGS={20143:'asset:///hub-ui/g-s.png',
         20925:'asset:///hub-ui/g-a.png',
         18935:'asset:///hub-ui/g-u.png',
         18936:'#202934',19884:'#f5f6f8',20569:''}

def additions(reader,replace):
 c=bytearray()
 def emit(name,*args):c.extend([getattr(ops,name).opcode,*args])
 def sid(name):return reader.strings.index(name)
 def get(dst,obj,prop):c.extend(bytes([ops.GetById.opcode,dst,obj,1])+struct.pack('<H',sid(prop)))
 def put(obj,value,prop):c.extend(bytes([ops.PutNewOwnById.opcode,obj,value])+struct.pack('<H',sid(prop)))
 def string(reg,n):c.extend(bytes([ops.LoadConstString.opcode,reg])+struct.pack('<H',n))
 def num(reg,n):emit('LoadConstUInt8',reg,n)
 def prop(obj,reg,key,n):num(reg,n);put(obj,reg,key)
 emit('LoadParam',17,1);emit('LoadParam',18,2);emit('LoadParam',19,3)
 emit('GetEnvironment',0,0);emit('GetEnvironment',1,1)
 emit('LoadFromEnvironment',2,1,4);get(2,2,'createElement')
 emit('LoadFromEnvironment',4,1,6)
 get(5,4,'View');get(6,4,'TouchableOpacity');get(7,4,'Text');get(8,4,'Image')
 emit('LoadConstUndefined',3);emit('NewArray',9,3,0)
 for index,(label,icon,callback) in enumerate([(20613,20143,23048),(19219,18935,23478),(20430,20925,23043)]):
  emit('NewObject',10);prop(10,11,'flex',1)
  string(11,sid('row'));put(10,11,'flexDirection');string(11,sid('center'));put(10,11,'alignItems');put(10,11,'justifyContent')
  emit('NewObject',12);put(12,10,'style');c.extend(bytes([ops.CreateClosure.opcode,11,0])+struct.pack('<H',callback));put(12,11,'onPress')
  emit('NewObject',13);prop(13,11,'width',32);prop(13,11,'height',32);prop(13,11,'marginRight',8)
  emit('NewObject',14);string(11,icon);put(14,11,'uri')
  emit('NewObject',15);put(15,13,'style');put(15,14,'source');string(11,sid('contain'));put(15,11,'resizeMode')
  emit('Call3',15,2,3,8,15)
  emit('NewObject',13);prop(13,11,'fontSize',14);string(11,19884);put(13,11,'color')
  emit('NewObject',14);put(14,13,'style');string(11,label);emit('Call4',14,2,3,7,14,11)
  emit('NewArray',13,2,0);emit('PutOwnByIndex',13,15,0);emit('PutOwnByIndex',13,14,1)
  emit('Call4',12,2,3,6,12,13);emit('PutOwnByIndex',9,12,index)
 # The official footer sits inside the hero's gradient, with no separate
 # surface or horizontal inset. Preserve the artwork and acceleration button.
 emit('NewObject',10);string(11,sid('row'));put(10,11,'flexDirection')
 string(11,sid('absolute'));put(10,11,'position')
 for name,n in [('left',16),('right',16),('bottom',16)]:prop(10,11,name,n)
 emit('NewObject',12);put(12,10,'style');emit('Call4',0,2,3,5,12,9)
 emit('NewArray',9,2,0);emit('PutOwnByIndex',9,18,0);emit('PutOwnByIndex',9,0,1)
 get(10,17,'style');emit('NewObject',12);prop(12,11,'paddingBottom',48)
 emit('NewArray',13,2,0);emit('PutOwnByIndex',13,10,0);emit('PutOwnByIndex',13,12,1)
 # style already exists in the original gradient props. PutNewOwnById is
 # only valid for fresh keys; using it here corrupts repeated shape writes.
 c.extend(bytes([ops.PutById.opcode,17,13,1])+struct.pack('<H',sid('style')))
 c.extend(bytes([ops.NewArrayWithBufferLong.opcode,13])+struct.pack('<HHI',2,2,69004))
 put(17,13,'locations');put(17,9,'children')
 emit('Call3',0,2,3,19,17);emit('Ret',0)
 replace(23472,bytes(c),'three shortcuts inside original game hero gradient footer')

def insert_row(reader,data,changed,changes):
 h=reader.function_headers[23365]
 def region(start,end,c):
  remaining=end-start-len(c);padding=bytearray()
  assert remaining>=0
  if remaining%3==1:padding+=bytes([ops.LoadConstUndefined.opcode,22]*2);remaining-=4
  elif remaining%3==2:padding+=bytes([ops.LoadConstUndefined.opcode,22]);remaining-=2
  padding+=bytes([ops.Mov.opcode,22,22])*(remaining//3)
  assert len(c+padding)==end-start
  data[h.offset+start:h.offset+end]=c+padding
 # Pass the original gradient properties, hero child, and component to the
 # footer renderer before creating the original outer wrapper.
 c=bytes([ops.CreateClosure.opcode,25,8])+struct.pack('<H',23472)
 c+=bytes([ops.Call4.opcode,17,25,3,6,20,17])
 region(0xbbf,0xbd8,c)
 region(0x16b7,0x17da,b'')
 small=reader.get_small_func_header_reader()
 raw=small.from_buffer(data,reader.functions_offset+23472*ctypes.sizeof(small))
 metadata=reader.get_large_func_header_reader().from_buffer(data,(raw.infoOffset<<16)|raw.offset) if raw.overflowed else raw
 metadata.paramCount=4
 changed.add(23365);changes.append({'function':23365,'action':'integrate shortcuts into hero gradient and erase game tips'})

def unknown_counts(reader,data,replace):
 # Preserve numeric zero; unavailable native action counts render as --.
 # The oct08 patch input differs from the installed official reference:
 # its stats producer is 23510; 23571 is the My-page Setting component.
 fid=23510
 assert reader.function_headers[fid].bytecodeSizeInBytes==493
 c=bytearray()
 def emit(name,*args):
  op=getattr(ops,name);raw=op.structure();assert len(args)==len(raw._fields_)
  for (field,kind),value in zip(raw._fields_,args):setattr(raw,field,value)
  c.append(op.opcode);c.extend(bytes(raw))
 def get(dst,obj,name):emit('GetById',dst,obj,1,reader.strings.index(name))
 def put(obj,value,name):emit('PutNewOwnById',obj,value,reader.strings.index(name))
 def string(reg,name):emit('LoadConstString',reg,reader.strings.index(name))
 def branch(name,*args):pos=len(c);emit(name,0,*args);return pos
 def finish(pos):
  delta=len(c)-pos;assert delta<128;c[pos+1]=delta
 emit('GetEnvironment',0,0);emit('LoadFromEnvironment',1,0,0)
 emit('GetEnvironment',2,1);emit('LoadFromEnvironment',3,2,0);emit('LoadFromEnvironment',4,2,1)
 emit('LoadConstUndefined',5);emit('LoadConstNull',6);emit('NewArray',8,3)
 for index,(category,label) in enumerate((('antiBother','防干扰'),('antiOccupy','防抢占'),('antiShake','防信号抖动'))):
  emit('LoadConstUInt8',15,5+index);emit('GetByVal',15,4,15);emit('Call2',14,3,5,15)
  emit('LoadConstUndefined',10);j=branch('JEqual',1,6);get(10,1,category);finish(j)
  string(11,'--');string(12,'--');emit('LoadConstUndefined',13)
  j=branch('JEqual',10,6)
  get(11,10,'todayCnt');q=branch('JNotEqual',11,6);string(11,'--');finish(q)
  get(12,10,'historyCnt');q=branch('JNotEqual',12,6);string(12,'--');finish(q)
  get(13,10,'unit');finish(j)
  emit('NewObject',9);string(15,label);put(9,15,'label');put(9,14,'icon')
  put(9,11,'todayCnt');put(9,12,'totalCnt');put(9,13,'unit');emit('PutOwnByIndex',8,9,index)
 emit('Ret',8)
 replace(fid,bytes(c),'AI statistics preserve unavailable counts instead of inventing zero')
 small=reader.get_small_func_header_reader();raw=small.from_buffer(data,reader.functions_offset+fid*ctypes.sizeof(small))
 meta=reader.get_large_func_header_reader().from_buffer(data,(raw.infoOffset<<16)|raw.offset) if raw.overflowed else raw
 meta.frameSize=max(meta.frameSize,28)
