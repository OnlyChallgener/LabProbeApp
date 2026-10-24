-- Independent native read-only telemetry. No official cloud configuration is changed.
local json = require('dkjson')
local configPath = arg[1] or '/etc/shinya-agent/config.json'
local function file(path)
  local f = assert(io.open(path,'r')); local s=f:read('*a');f:close();return s
end
local config = assert(json.decode(file(configPath)))
local ratesOk, wifiRates = pcall(dofile, '/etc/shinya-agent/wifi_rates.lua')
assert(type(config.sn)=='string' and config.sn:match('^[%w_-]+$'))
local runId = file('/proc/sys/kernel/random/boot_id'):gsub('%s','')..':'..
              file('/proc/sys/kernel/random/uuid'):gsub('%s','')
local modules = {
  {'sysinfo',nil,60}, {'user_list'}, {'flow','{"func":"interface_info"}'},
  {'overview',nil,15}, {'radioInfo',nil,30}, {'ap_list',nil,60}
}
local nextDue={}
local function quote(s) return "'"..s:gsub("'","'\\''").."'" end
local function readNative(module,payload)
  local command='timeout -t 6 dev_sta get -m '..module
  if payload then command=command..' '..quote(payload) end
  local pipe=assert(io.popen(command..' 2>/dev/null','r'))
  local text=pipe:read('*a');pipe:close()
  local value,_,err=json.decode(text)
  if err or type(value)~='table' then error('Invalid native result') end
  for _=1,6 do
    if value.rcode and value.rcode~='00000000' then error('Native read rejected') end
    if value.code and tostring(value.code)~='0' then error('Native read failed') end
    -- dev_sta's command envelope stores its native result as a JSON string.
    if type(value.data)=='string' then
      value=assert(json.decode(value.data))
    elseif value.message=='success' and type(value.data)=='table' then
      value=value.data
    else break end
  end
  if type(value)~='table' then error('Missing native result') end
  return value
end
local sequence=0
repeat
  sequence=sequence+1
  local resources={}
  for _,item in ipairs(modules) do
    if not nextDue[item[1]] or os.time()>=nextDue[item[1]] then
    local ok,value=pcall(readNative,item[1],item[2])
    if ok then
      if item[1]=='sysinfo' then
        assert((value.serial_num or value.serialNumber or value.sn)==config.sn,'Wrong physical router')
      end
      if item[1]=='user_list' and ratesOk then
        -- A failed supplement must not discard the native terminal snapshot.
        pcall(wifiRates.enrich,value,config.sn)
      end
      resources[#resources+1]={module=item[1],value=value,observedAt=os.time()}
      nextDue[item[1]]=os.time()+(item[3] or math.max(5,tonumber(config.interval) or 5))
    end
    end
  end
  if #resources>0 then
    local frame=assert(json.encode({version=1,sn=config.sn,runId=runId,sequence=sequence,resources=resources}))
    if config.outputPath then
      -- /tmp is RAM: an atomic replacement bounds offline backlog to one frame.
      local temporary=config.outputPath..'.next'
      local output=assert(io.open(temporary,'w'));assert(output:write(frame));assert(output:close())
      assert(os.rename(temporary,config.outputPath))
    else
      io.write(frame..'\n');assert(io.flush())
    end
  end
  if config.once then break end
  os.execute('sleep '..tostring(math.max(5,math.min(300,tonumber(config.interval) or 5))))
until false
