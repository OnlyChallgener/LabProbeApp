#! /usr/bin/lua
-- HUB-facing exec module. Only three verbs exist and every shell token is rebuilt
-- from a validated vocabulary; the request never contributes shell syntax.
package.path = "/usr/lib/lua/?.lua;" .. package.path;
module("shinya_exec", package.seeall);

local dkjson = require("dkjson");

local SURVEY_TIMEOUT = 15
local READ_TIMEOUT = 6
local OUTPUT_LIMIT = 220000
local READ_PREFIXES = {"/etc/rg_config/", "/proc/net/", "/tmp/ctcapd/"}
local INTERFACES = {["2.4G"] = "ra0", ["5G"] = "rai0", ["5G-2"] = "rax0"}

-- BusyBox' timeout execs with execv and never searches PATH, so every command
-- handed to it has to be absolute even though the shell resolves bare names.
local function locate(name, fallback)
  local pipe = io.popen("command -v " .. name .. " 2>/dev/null", "r");
  local found = (pipe:read("*a") or ""):gsub("%s+$", "");
  pipe:close();
  if found ~= "" then return found end
  return fallback;
end
IWPRIV = locate("iwpriv", "/usr/sbin/iwpriv");
IWCONFIG = locate("iwconfig", "/usr/sbin/iwconfig");
WLANCONFIG = locate("wlanconfig", "/sbin/wlanconfig");
IWINFO = locate("iwinfo", "/usr/bin/iwinfo");
IW = locate("iw", "/usr/sbin/iw");
CAT = locate("cat", "/bin/cat");

-- Column offsets copied from the firmware's own parser
-- (/usr/local/lua/dev_sta/radio_scan.lua:scan_format), which is the only
-- trustworthy reading of iwpriv's fixed-width table.
local function parse_row(line)
  if not line or #line < 42 then return nil end
  local channel = line:sub(5, 8):match("%d+")
  if not channel then return nil end
  local ssid = line:sub(9, 41):gsub("%s+$", "");
  local bssid, security, signal, mode, rest = line:sub(42):match("^(%S+)%s+(%S+)%s+(%-?%d+)%s+(%S+)%s*(.*)$")
  if not (bssid and security and signal and mode) then return nil end
  local width = (rest or ""):match("(%d+)M")
  return {channel = tonumber(channel), ssid = ssid, bssid = bssid, security = security,
          signal = tonumber(signal), mode = mode, width = tonumber(width)}
end

-- iwpriv reports its own BSS entries with signal 0; the official payload keeps
-- those rows and marks them, so the neighbour count matches what the vendor App
-- shows. Do not invent a stronger signal for them.
local function is_self(entry, own)
  local key = entry.ssid .. "|" .. entry.bssid:lower();
  return own[key] == true
end

local function own_bssids(band)
  local set, rows = {}, {};
  local cursor = require("luci.model.uci").cursor();
  local wireless_path = cursor:get("wifiinfo", "global", "wireless_path") or "/etc/config/";
  cursor:set_confdir(wireless_path);
  cursor:foreach("wireless", "wifi-iface", function(section)
    local name = cursor:get("wireless", section[".name"], "ssid") or "";
    local ifname = cursor:get("wireless", section[".name"], "ifname") or "";
    local device = cursor:get("wireless", section[".name"], "device") or "";
    local disabled = cursor:get("wireless", section[".name"], "disabled") or "0";
    local radio_band = cursor:get("wireless", device, "band") or "";
    -- The reserved hidden mesh BSS is not an editable household SSID.
    if disabled ~= "1" and name ~= "" and name ~= "down" and name ~= "@Ruijie-Repeater"
        and radio_band == band and ifname:match("^rai?%d+$") then
      -- MAC is not a UCI option on these models. Read the active AP interface,
      -- whose Channel/Access Point values describe this radio, not a phone.
      local pipe = io.popen("timeout -t " .. READ_TIMEOUT .. " " .. IWCONFIG .. " " .. ifname .. " 2>/dev/null", "r");
      local info = pipe:read("*a") or ""; pipe:close();
      local mac = info:match("Access Point:%s*([%x:]+)");
      local channel = tonumber(info:match("Channel[:=](%d+)"));
      local mode = info:match("IEEE 802%.11([^%s]+)") or "";
      local width = tonumber((cursor:get("wireless", device, "htmode") or ""):match("(%d+)")) or 20;
      if mac and mac:match("^%x%x:%x%x:%x%x:%x%x:%x%x:%x%x$") and channel then
        set[name .. "|" .. mac:lower()] = true;
        rows[#rows + 1] = {channel=channel,ssid=name,bssid=mac:lower(),signal=0,
          mode=mode,width=width,isSelfSsid=true,iface=ifname,source="local-radio"};
      end;
    end;
  end);
  return set, rows;
end

local function survey(band)
  local iface = INTERFACES[band];
  if not iface then return nil, "unknown band" end
  local pipe = io.popen("timeout -t " .. SURVEY_TIMEOUT .. " " .. IWPRIV ..
                        " " .. iface .. " set SiteSurvey=1; sleep 1; timeout -t " .. (SURVEY_TIMEOUT + 5) ..
                        " " .. IWPRIV .. " " .. iface .. " get_site_survey 2>/dev/null", "r");
  local text = pipe:read("*a") or "";
  pipe:close();
  local rows = {};
  local own, own_rows = own_bssids(band);
  for line in text:gmatch("[^\r\n]+") do
    local entry = parse_row(line);
    if entry then
      entry.iface = iface;
      entry.isSelfSsid = is_self(entry, own);
      rows[#rows + 1] = entry;
    end
  end
  -- Site-survey tables omit the transmitting AP on both routers. Append real
  -- active local BSSes; zero is the native self value and is rendered as 本机.
  local known = {}; for _, entry in ipairs(rows) do known[entry.bssid:lower()] = true end;
  for _, entry in ipairs(own_rows) do
    if not known[entry.bssid] then rows[#rows + 1] = entry end;
  end;
  if #rows == 0 then return nil, iface .. " produced no survey rows" end
  return rows;
end

local function shell(command)
  local pipe = io.popen("timeout -t " .. READ_TIMEOUT .. " " .. command .. " 2>/dev/null", "r")
  local text = pipe:read("*a") or "";
  pipe:close();
  return text;
end

-- Per-station rate, width and spatial streams.
--
-- Three readers, and none of them is complete alone:
--   `wlanconfig <radio> list` covers every associated station and carries the negotiated
--       rates plus the PHY mode, but has no bandwidth column at all;
--   `iwinfo <bss> assoclist` and `iw dev <bss> station dump` carry the exact width and
--       stream count, but each knows only the BSS it is asked about and only while the
--       station is reporting -- measured on these two units they answered for 2 of 6.
-- Nothing is estimated inside the module: the estimate belongs on the HUB side, where it
-- can be tested against the samples these two readers do return.
--
-- The table's values are right-aligned under a header whose column widths differ, and its
-- SSID column may itself contain spaces, so a row is read as its first twelve columns, its
-- last thirteen, and whatever is left in between. Slicing at the header's offsets instead
-- makes every earlier field swallow the next one.
local HEAD_COLUMNS = {"ADDR", "AID", "CHAN", "TXRATE", "RXRATE", "RSSI", "MAXRATE",
                      "ASSOCTIME", "CHUTIL", "FLNOSIE", "PSM", "IFNAME"};
local TAIL_COLUMNS = {"WIFIUP", "WIFIDOWN", "TIMEDELAY", "CAP", "UPPKG", "DOWNPKG",
                      "UPPKGRETRY", "DOWNPKGRETRY", "UPPKGLOSS", "DOWNPKGLOSS",
                      "DELAYAVG", "PHMD", "MLO"};

local function station_table(radio)
  local text = shell(WLANCONFIG .. " " .. radio .. " list");
  if not text:match("^ADDR") then return nil, radio .. " returned no station table" end
  local rows = {};
  for line in text:gmatch("[^\r\n]+") do
    if line:match("^%x%x:%x%x:%x%x:%x%x:%x%x:%x%x") then
      local tokens = {};
      for token in line:gmatch("%S+") do tokens[#tokens + 1] = token end
      if #tokens >= #HEAD_COLUMNS + #TAIL_COLUMNS then
        local row = {};
        for index, name in ipairs(HEAD_COLUMNS) do row[name] = tokens[index] end
        for index, name in ipairs(TAIL_COLUMNS) do
          row[name] = tokens[#tokens - #TAIL_COLUMNS + index];
        end
        local words = {};
        for index = #HEAD_COLUMNS + 1, #tokens - #TAIL_COLUMNS do
          words[#words + 1] = tokens[index];
        end
        row.SSID = table.concat(words, " ");
        rows[#rows + 1] = row;
      end
    end
  end
  return rows;
end

local function modulation(line)
  return line:match("(EHT%s*%-?MCS%s*%d+)") or line:match("(HE%s*%-?MCS%s*%d+)")
      or line:match("(VHT%s*%-?MCS%s*%d+)") or line:match("(HT%s*%-?MCS%s*%d+)")
      or line:match("(MCS%s*%d+)") or "";
end

local function negotiate(line)
  local width = tonumber(line:match("(%d+)MHz"));
  if not width then return nil end
  return {width = width, modulation = modulation(line):gsub("%s+", " "),
          streams = tonumber(line:match("NSS%s*(%d+)"))};
end

-- iwinfo reports only the BSS it is asked about, so every SSID interface is read apart.
local function associated(iface)
  local order, rows, current = {}, {}, nil;
  for line in shell(IWINFO .. " " .. iface .. " assoclist"):gmatch("[^\r\n]+") do
    local mac = line:match("^(%x%x:%x%x:%x%x:%x%x:%x%x:%x%x)");
    if mac then
      mac = mac:lower();
      if not rows[mac] then
        rows[mac] = {mac = mac, signal = tonumber(line:match("(%-?%d+) dBm"))};
        order[#order + 1] = mac;
      end
      current = rows[mac];
    elseif current then
      local width = negotiate(line);
      if width and not current.width then
        current.width, current.modulation, current.streams =
            width.width, width.modulation, width.streams;
        current.source = "iwinfo";
      end
    end
  end
  return order, rows;
end

local function dumped(iface)
  local order, rows, current = {}, {}, nil;
  for line in shell(IW .. " dev " .. iface .. " station dump"):gmatch("[^\r\n]+") do
    local mac = line:match("^Station (%x%x:%x%x:%x%x:%x%x:%x%x:%x%x)");
    if mac then
      mac = mac:lower();
      if not rows[mac] then
        rows[mac] = {mac = mac};
        order[#order + 1] = mac;
      end
      current = rows[mac];
    elseif current and line:match("bitrate") then
      local width = negotiate(line);
      if width and not current.width then
        current.width, current.modulation, current.streams =
            width.width, width.modulation, width.streams;
        current.source = "iw";
      end
    end
  end
  return order, rows;
end

-- The radio's own configured width caps anything on it, and unlike the per-station readers
-- it always answers. `HT Mode: VHT160` is what iwinfo prints for this driver family.
local RADIO_WIDTH = {HT20 = 20, HT40 = 40, VHT20 = 20, VHT40 = 40, VHT80 = 80, VHT160 = 160,
                     HE20 = 20, HE40 = 40, HE80 = 80, HE160 = 160, EHT20 = 20, EHT40 = 40,
                     EHT80 = 80, EHT160 = 160};

local function radio_state(iface)
  local mode = shell(IWINFO .. " " .. iface .. " info"):match("HT Mode:%s*(%S+)");
  return mode, (mode and RADIO_WIDTH[mode]) or nil;
end

local function stations(radio)
  local rows, reason = station_table(radio);
  if not rows then return nil, reason end
  local mode, cap = radio_state(radio);
  local measured, read = {}, {};
  for _, row in ipairs(rows) do
    local iface = row.IFNAME;
    if iface and iface:match("^ra[ix]?%d$") and not read[iface] then
      read[iface] = true;
      local order, entries = associated(iface);
      local gaps = #order == 0;
      for _, mac in ipairs(order) do
        if not entries[mac].width then gaps = true end
      end
      -- iw is only paid for when iwinfo printed a rate without a width, which is the
      -- BE50's 5G shape; the BE72 and both units' 2.4G side answer on the first reader.
      local pools = {{order, entries}};
      if gaps then pools[#pools + 1] = {dumped(iface)} end
      for _, pool in ipairs(pools) do
        for _, mac in ipairs(pool[1]) do
          local entry = pool[2][mac];
          if entry and entry.width and not measured[mac] then measured[mac] = entry end
        end
      end
    end
  end
  local result = {};
  for _, row in ipairs(rows) do
    local mac = row.ADDR:lower();
    local entry = measured[mac];
    result[#result + 1] = {mac = mac, interface = row.IFNAME, radio = radio, ssid = row.SSID,
                           channel = tonumber(row.CHAN), txRate = row.TXRATE, rxRate = row.RXRATE,
                           maxRate = row.MAXRATE, phy = row.PHMD, rssi = row.RSSI,
                           utilisation = row.CHUTIL, associatedSince = row.ASSOCTIME,
                           mlo = row.MLO, radioMode = mode, radioWidth = cap,
                           measuredWidth = entry and entry.width,
                           measuredModulation = entry and entry.modulation,
                           measuredStreams = entry and entry.streams,
                           measuredSource = entry and entry.source};
  end
  table.sort(result, function(a, b) return a.mac < b.mac end);
  return result;
end

local function read_file(path)
  local allowed = false;
  for _, prefix in ipairs(READ_PREFIXES) do
    if path:sub(1, #prefix) == prefix then allowed = true end
  end
  if not allowed then return nil, "path is not on the readable list" end
  local pipe = io.popen("timeout -t " .. READ_TIMEOUT .. " " .. CAT .. " " .. path .. " 2>/dev/null");
  local text = pipe:read("*a") or "";
  pipe:close();
  return text;
end

function module_get(param)
  local request = dkjson.decode(param or "{}") or {};
  local verb = request.action or "survey";
  if verb == "survey" then
    local bands = request.bands or {"2.4G", "5G"};
    local result = {action = "survey", observedAt = os.time(), bands = {}};
    local failures = {};
    for _, band in ipairs(bands) do
      local rows, reason = survey(band);
      if rows then result.bands[band] = rows else failures[band] = reason end
    end
    result.errors = failures;
    return dkjson.encode(result);
  elseif verb == "ipv6" then
    -- All paths and commands are fixed; the request cannot supply an interface,
    -- file name, destination, prefix or shell argument. No probes/config writes.
    local function bounded(path, limit)
      local file = io.open(path, "r");
      if not file then return "" end
      local text = file:read(limit) or ""; file:close(); return text;
    end
    local ip = locate("ip", "/sbin/ip");
    local function capture(arguments, limit)
      local pipe = io.popen("timeout -t 2 " .. ip .. " " .. arguments .. " 2>/dev/null", "r");
      if not pipe then return "" end
      local text = pipe:read(limit) or ""; pipe:close(); return text;
    end
    local interfaces = {};
    local bridge = io.popen("for p in /sys/class/net/*/bridge; do [ -d \"$p\" ] && basename \"${p%/bridge}\"; done", "r");
    if bridge then
      local text = bridge:read(8192) or ""; bridge:close();
      for name in text:gmatch("[%w_.%-]+") do interfaces[#interfaces + 1] = name end
    end
    return dkjson.encode({action = "ipv6", observedAt = os.time(),
      uptime = tonumber(bounded("/proc/uptime", 80):match("^(%d+%.?%d*)")),
      bootId = bounded("/proc/sys/kernel/random/boot_id", 80):gsub("%s", ""),
      lanInterfaces = interfaces, neighbors = capture("-6 neigh show", 65536),
      localAddresses = capture("-6 addr show", 32768), leases = bounded("/tmp/dhcp.leases", 65536)});
  elseif verb == "stations" then
    local radios = request.radios or {"ra0", "rai0"};
    local result = {action = "stations", observedAt = os.time(), radios = {}, errors = {}};
    for _, iface in ipairs(radios) do
      -- Only names from this shape ever reach the shell; the request cannot add syntax.
      if type(iface) ~= "string" or not iface:match("^ra[ix]?%d$") then
        result.errors[tostring(iface)] = "interface name is not on the fixed list";
      else
        local rows, reason = stations(iface);
        if rows then
          if #rows > 0 then
            result.radios[#result.radios + 1] = {radio = iface, rows = rows};
          end
        else
          result.errors[iface] = reason;
        end
      end
    end
    return dkjson.encode(result);
  elseif verb == "read" then
    local path = request.path;
    if type(path) ~= "string" or not path:match("^[%w%._/%-]+$") then
      return dkjson.encode({action = "read", error = "path is required"});
    end
    local text, reason = read_file(path);
    if text == nil then return dkjson.encode({action = "read", path = path, error = reason}) end
    if #text > OUTPUT_LIMIT then text = text:sub(1, OUTPUT_LIMIT) end
    return dkjson.encode({action = "read", path = path, observedAt = os.time(), contents = text});
  end
  return dkjson.encode({error = "unknown action"});
end

function module_set(param)
  return module_get(param);
end
