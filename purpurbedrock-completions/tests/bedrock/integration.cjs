// Test-only offline clients, with unique XUIDs, on an isolated CI server.
const assert = require('node:assert/strict')
const { createHash, randomUUID } = require('node:crypto')
const bedrock = require('bedrock-protocol')
const clients = []
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
function name(label, prefix = []) {
  const input = label + '\0' + prefix.join('\0') + 'S'
  return `${label}_arg${prefix.length + 1}_${createHash('sha256').update(input).digest('hex').slice(0, 16)}`
}
function connect(username, xuid) {
  const state = { username, enums: new Map(), packets: [], spawned: false }
  clients.push(state)
  const client = state.client = bedrock.createClient({ host: '127.0.0.1', port: 19132,
    version: '1.26.51', username, offline: true, transport: 'raknet', conLog: () => {} })
  client.prependOnceListener('session', profile => { profile.xuid = xuid })
  client.on('error', e => { state.error = e })
  client.on('disconnect', packet => { if (!state.closing) state.error = new Error(JSON.stringify(packet)) })
  client.on('spawn', () => { state.spawned = true; client.queue('request_chunk_radius', { chunk_radius: 4, max_radius: 4 }) })
  client.on('available_commands', packet => {
    state.packets.push(packet)
    state.enums = new Map(packet.dynamic_enums.map(entry => [entry.name, entry.values]))
  })
  client.on('update_soft_enum', packet => {
    const old = state.enums.get(packet.enum_type) || []
    state.enums.set(packet.enum_type, packet.action_type === 'update' ? packet.options :
      packet.action_type === 'add' ? [...new Set([...old, ...packet.options])] : old.filter(v => !packet.options.includes(v)))
  })
  return state
}
async function until(label, test, timeout = 90000) {
  const end = Date.now() + timeout
  while (Date.now() < end) {
    for (const state of clients) if (state.error) throw state.error
    if (test()) { console.log('PASS', label); return }
    await pause(100)
  }
  throw new Error('Timed out: ' + label)
}
function values(state, root, prefix = []) { return state.enums.get(name(root, prefix)) || [] }
function command(state, text) {
  state.client.queue('command_request', { command: text,
    origin: { type: 'player', uuid: randomUUID(), request_id: '', player_entity_id: 0n }, internal: false, version: '' })
}
async function main() {
  const a = connect('TabTestA', '10001'), b = connect('TabTestB', '10002')
  await until('two clients spawned', () => a.spawned && b.spawned)
  await until('individual first-argument homes', () => values(a, 'home').includes('TabTestA_base') && values(b, 'home').includes('TabTestB_base'))
  assert(!values(a, 'home').includes('TabTestB_base'))
  assert(!values(b, 'home').includes('TabTestA_base'))
  await until('nested delete options', () => values(a, 'delhome', ['delete']).includes('TabTestA_base') && values(b, 'delhome', ['delete']).includes('TabTestB_base'))
  assert(!values(b, 'delhome', ['delete']).includes('TabTestA_base'))
  command(a, '/delhome set new_home')
  await until('new home refreshed', () => values(a, 'home').includes('new_home') && values(a, 'delhome', ['delete']).includes('new_home'))
  assert(!values(b, 'home').includes('new_home'))
  command(a, '/delhome delete new_home')
  await until('deleted home withdrawn', () => !values(a, 'home').includes('new_home') && !values(a, 'delhome', ['delete']).includes('new_home'))
  assert(values(b, 'home').includes('TabTestB_base'))
  for (const state of [a, b]) {
    for (const packet of state.packets) {
      const names = packet.command_data.flatMap(definition => {
        const aliases = packet.enums[definition.alias]
        return [definition.name, ...(aliases ? aliases.values.map(index => packet.enum_values[index]) : [])]
      })
      assert(!names.includes('privatetest'), 'Unauthorized command exposed')
    }
    assert(![...state.enums.values()].some(list => list.includes('secret_value')), 'Unauthorized completion exposed')
  }
  console.log('PASS player isolation, permissions and dynamic updates')
}
main().then(() => { process.exitCode = 0 }).catch(e => { console.error(e); process.exitCode = 1 })
  .finally(async () => { for (const state of clients) { state.closing = true; state.client.close() }; await pause(500); process.exit(process.exitCode) })
