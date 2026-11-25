-- Run with the router's Lua 5.1, without reading hardware or changing config.
local sensor = dofile(arg[1])
assert(sensor.parse_radio('CurrentTemperature\t = 61\n') == 61)
assert(sensor.parse_radio('CurrentTemperature = -3.5') == -3.5)
assert(sensor.parse_radio('CurrentTemperature = 999') == nil)
assert(sensor.parse_radio('Temperature = 61') == nil)
assert(sensor.parse_radio('') == nil)
sensor.read = function()
  return {temp=60.2,temp_2g=61,temp_5g=62,temperatureObservedAt=123,
    temperatureSource={temp='cpu',temp_2g='ra0',temp_5g='rai0'}}
end
local result = sensor.enrich({}, 'BE50-TEST-SN')
assert(result.temp == 60.2 and result.temp_2g == 61 and result.temp_5g == 62)
assert(result.temperatureSource.temp == 'cpu' and result.temperatureObservedAt == 123)
assert(sensor.enrich({}, 'BE50-OTHER-TEST').temp == 60.2)
assert(sensor.enrich({temp=70}, 'BE50-TEST-SN').temp == 70)
sensor.read = function() return {temperatureSource={}} end
assert(sensor.enrich({}, 'BE50-TEST-SN').temp == nil)
print('temperature parser, native preservation and missing sensors: PASS')
