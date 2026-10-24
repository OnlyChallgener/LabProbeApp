-- Supplement native station identities with verified AP association rates.
local M = {}
local method, retryAt = {}, {}
local function mac(s)
  if type(s)~='string' then return nil end
  s=s:gsub(':',''):upper()
  if #s==12 and s:match('^%x+$') then return s end
end
local function rate(s)
  if type(s)~='string' then return nil end
  local v=s:match('^(%d+%.?%d*)M$')
  v=tonumber(v)
  if v and v>=0 and v<=100000 then return v end
end
local function iface(s) return type(s)=='string' and (s:match('^ra%d+$') or s:match('^rai%d+$')) end
function M.parse(text)
  if type(text)~='string' or #text>65536 then return {} end
  local result,first={},true
  for line in text:gmatch('[^\r\n]+') do
    local fields={}
    -- Parse only the known prefix through IFNAME. SSID and packet statistics
    -- may contain spaces and are deliberately not split or interpreted.
    for token in line:gmatch('%S+') do fields[#fields+1]=token;if #fields==12 then break end end
    if first then
      if fields[1]~='ADDR' or fields[4]~='TXRATE' or fields[5]~='RXRATE' or fields[12]~='IFNAME' then return {} end
      first=false
    else
      local key=mac(fields[1]);local down,up=rate(fields[4]),rate(fields[5]);local name=fields[12]
      if key and iface(name) and down and up then
        local id=key..'/'..name
        if result[id]~=nil then result[id]=false else result[id]={down=down,up=up,ifname=name} end
      end
    end
  end
  return result
end
local function read(interface)
  if not iface(interface) or (retryAt[interface] or 0)>os.time() then return {} end
  local function query(suffix)
    local p=io.popen('timeout -t 2 wlanconfig '..interface..' '..suffix..' 2>/dev/null','r')
    if not p then return {} end
    local data=p:read(65537);p:close()
    return M.parse(data)
  end
  local suffix=method[interface] or 'list show_all_stainfo'
  local rows=query(suffix)
  if not next(rows) and not method[interface] then
    suffix='list';rows=query(suffix)
  end
  if next(rows) then method[interface]=suffix else retryAt[interface]=os.time()+30 end
  return rows
end
function M.enrich(value,sn,query)
  if type(value)~='table' or type(value.list)~='table' then return value end
  local wanted={}
  for _,row in ipairs(value.list) do
    if row.connectType=='wireless' and row.sn==sn and iface(row.ifname) then
      wanted[row.ifname:match('^rai') and 'rai0' or 'ra0']=true
    end
  end
  local rows={}
  for _,radio in ipairs({'ra0','rai0'}) do
    if wanted[radio] then
      local ok,data=pcall(query or read,radio)
      if ok and type(data)=='table' then for key,row in pairs(data) do rows[key]=row end end
    end
  end
  for _,row in ipairs(value.list) do
    local key=mac(row.mac)
    local sample=key and rows[key..'/'..tostring(row.ifname)]
    if row.connectType=='wireless' and row.sn==sn and sample then
      -- AP RXRATE is the terminal's upload. The existing native download
      -- field is preserved; unrelated association entries never create devices.
      row.txrate=tostring(sample.up)..'M'
      row.phyRateSource='wlanconfig.RXRATE';row.phyRateSn=sn;row.phyRateObservedAt=os.time()
    end
  end
  return value
end
return M
