// Disposable CI server, genuine EssentialsX, synthetic Bedrock connections (no UI rendering).
const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const bedrock = require('bedrock-protocol')
const pause = ms => new Promise(r => setTimeout(r, ms))
const clients = []
function connect(username, xuid) {
  const s = { username, enums: new Map(), spawned: false, texts: [] }; clients.push(s)
  const c = s.client = bedrock.createClient({ host: '127.0.0.1', port: 19132, version: '1.26.51', username, offline: true, transport: 'raknet', conLog: () => {} })
  c.prependOnceListener('session', p => { p.xuid = xuid })
  c.on('error', e => { s.error = String(e) }); c.on('disconnect', e => { if (!s.closing) s.error = JSON.stringify(e) })
  c.on('spawn', () => { s.spawned = true; c.queue('request_chunk_radius', { chunk_radius: 4, max_radius: 4 }) })
  c.on('available_commands', p => { s.latest = p; s.enums = new Map(p.dynamic_enums.map(e => [e.name, e.values])) })
  c.on('update_soft_enum', p => { const old = s.enums.get(p.enum_type) || []; s.enums.set(p.enum_type, p.action_type === 'update' ? p.options : p.action_type === 'add' ? [...new Set([...old, ...p.options])] : old.filter(v => !p.options.includes(v))) })
  c.on('text', p => { if (typeof p.message === 'string') s.texts.push(p.message) })
  return s
}
const parserIds = { int: 1, float: 3, value: 4, wildcard_int: 5, operator: 6, command_operator: 7, target: 8, wildcard_target: 10, file_path: 17, integer_range: 23, equipment_slots: 43, string: 44, block_position: 52, position: 53, message: 55, raw_text: 58, json: 62, block_states: 71, command: 75 }
function index(x) { return typeof x === 'string' ? parserIds[x] : x }
function root(s, name) { return s.latest?.command_data.find(d => d.name === name || (s.latest.enums[d.alias]?.values || []).some(i => s.latest.enum_values[i] === name)) }
function choices(s, name) {
  const def = root(s, name); if (!def) return []
  return def.overloads.flatMap(o => o.parameters).flatMap(p => {
    if (p.enum_type === 'soft_enum') return s.enums.get(s.latest.dynamic_enums[index(p.value_type)]?.name) || []
    if (p.enum_type === 'enum') return (s.latest.enums[index(p.value_type)]?.values || []).map(i => s.latest.enum_values[i])
    return []
  })
}
function command(s, text) { s.client.queue('command_request', { command: text, origin: { type: 'player', uuid: randomUUID(), request_id: '', player_entity_id: 0n }, internal: false, version: '' }) }
async function until(label, f, timeout = 60000) {
  const end = Date.now() + timeout
  while (Date.now() < end) { for (const s of clients) if (s.error) throw Error(s.error); if (f()) return; await pause(100) }
  throw Error('Timed out: ' + label)
}
async function main() {
  const a = connect('RealTabA', '20101'), b = connect('RealTabB', '20102')
  await until('both players spawned', () => a.spawned && b.spawned, 90000)
  await pause(2500)
  command(a, '/sethome alicebase'); await pause(1500)
  command(b, '/sethome bobbase'); await pause(1500)
  command(a, '/setwarp plaza'); await pause(1500)
  // Execution changes trigger a per-player refresh; avoid changing state while scanning.
  const untilTime = Date.now() + 100000
  while (Date.now() < untilTime) {
    if (choices(a, 'home').includes('alicebase') && choices(b, 'home').includes('bobbase') && choices(a, 'warp').includes('plaza') && choices(a, 'tpa').includes('RealTabB')) break
    await pause(200)
  }
  const detail = Object.fromEntries(['home', 'warp', 'tpa'].map(name => {
    const d = root(a, name)
    return [name, { root: d?.name, overloads: d?.overloads.map(o => o.parameters.map(p => ({ name: p.parameter_name, enum: p.enum_type, type: p.value_type }))), choices: choices(a, name).slice(0, 20) }]
  }))
  const checks = { alice: choices(a, 'home').includes('alicebase'), bob: choices(b, 'home').includes('bobbase'),
    isolated: !choices(b, 'home').includes('alicebase') && !choices(a, 'home').includes('bobbase'),
    warp: choices(a, 'warp').includes('plaza'), tpa: choices(a, 'tpa').includes('RealTabB') }
  console.log('::notice title=Real Essentials completion test::' + JSON.stringify({ checks, detail, messages: a.texts.slice(-8) }))
  assert(Object.values(checks).every(Boolean), 'Missing real EssentialsX completions or privacy violation')
}
main().catch(e => { console.error(e); process.exitCode = 1 }).finally(async () => { for (const s of clients) { s.closing = true; s.client.close() }; await pause(300); process.exit(process.exitCode || 0) })
