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
  const state = { username, enums: new Map(), packets: [], spawned: false,
    playerEntries: new Map(), players: new Map(), items: new Map(), armor: new Map(), hands: new Map(), skinUpdates: [] }
  clients.push(state)
  const client = state.client = bedrock.createClient({ host: '127.0.0.1', port: 19132,
    version: '1.26.51', username, offline: true, transport: 'raknet', conLog: () => {} })
  client.prependOnceListener('session', profile => { profile.xuid = xuid })
  client.on('error', e => { state.error = e })
  client.on('disconnect', packet => { if (!state.closing) state.error = new Error(JSON.stringify(packet)) })
  client.on('spawn', () => { state.spawned = true; client.queue('request_chunk_radius', { chunk_radius: 4, max_radius: 4 }) })
  client.on('item_registry', packet => {
    for (const item of packet.itemstates) state.items.set(item.name, item.runtime_id)
  })
  client.on('player_list', packet => {
    for (const entry of packet.records || []) {
      if (entry.type === 'add' && entry.username) state.playerEntries.set(entry.username, entry)
    }
  })
  client.on('add_player', packet => state.players.set(packet.username, packet))
  client.on('player_skin', packet => state.skinUpdates.push(packet))
  client.on('mob_armor_equipment', packet => state.armor.set(String(packet.runtime_entity_id), packet))
  client.on('mob_equipment', packet => state.hands.set(String(packet.runtime_entity_id) + ':' + packet.window_id, packet))
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
  await until('remote player actor and placeholder entry', () => a.players.has('TabTestB') && a.playerEntries.has('TabTestB'))
  const remote = a.players.get('TabTestB')
  const remoteEntry = a.playerEntries.get('TabTestB')
  assert.equal(remoteEntry.skin_data.skin_id, 'geysermc:empty', 'remote skin must be the fixed missing-texture placeholder')
  assert.equal(remoteEntry.skin_data.skin_data.width, 64)
  assert.equal(remoteEntry.skin_data.skin_data.height, 64)
  assert.equal(remoteEntry.skin_data.cape_data.width, 0)
  const runtimeId = String(remote.runtime_id)
  const item = itemName => a.items.get('minecraft:' + itemName)
  const armor = () => a.armor.get(runtimeId)
  const mainHand = () => a.hands.get(runtimeId + ':inventory')
  const offHand = () => a.hands.get(runtimeId + ':offhand')
  await until('item registry', () => ['diamond_helmet', 'iron_chestplate', 'golden_leggings',
    'chainmail_boots', 'iron_sword', 'shield'].every(x => item(x) !== undefined))
  command(b, '/gearfixture on')
  await until('remote armor and both hand items delivered', () =>
    armor()?.helmet.network_id === item('diamond_helmet') &&
    armor()?.chestplate.network_id === item('iron_chestplate') &&
    armor()?.leggings.network_id === item('golden_leggings') &&
    armor()?.boots.network_id === item('chainmail_boots') &&
    mainHand()?.item.network_id === item('iron_sword') && offHand()?.item.network_id === item('shield'))
  command(b, '/gearfixture off')
  await until('removed armor and both hand items updated', () =>
    armor()?.helmet.network_id === 0 && armor()?.chestplate.network_id === 0 &&
    armor()?.leggings.network_id === 0 && armor()?.boots.network_id === 0 &&
    mainHand()?.item.network_id === 0 && offHand()?.item.network_id === 0)
  await until('custom and vanilla paper registered', () =>
    a.items.has('geyser_custom:sphere1') && a.items.has('minecraft:paper'))
  const customSphere = a.items.get('geyser_custom:sphere1')
  const vanillaPaper = a.items.get('minecraft:paper')
  assert.notEqual(customSphere, vanillaPaper, 'custom and vanilla item registry IDs must be different')
  command(b, '/modelfixture custom')
  await until('matching item model uses the custom item', () => mainHand()?.item.network_id === customSphere)
  command(b, '/modelfixture vanilla')
  await until('ordinary paper remains vanilla', () => mainHand()?.item.network_id === vanillaPaper)
  command(b, '/modelfixture custom')
  await until('matching item model reapplied', () => mainHand()?.item.network_id === customSphere)
  command(b, '/modelfixture other')
  await until('unmatched item model remains vanilla', () => mainHand()?.item.network_id === vanillaPaper)
  await until('marker-guarded item registered', () => a.items.has('purpurbedrock_test:guarded_paper'))
  const guardedPaper = a.items.get('purpurbedrock_test:guarded_paper')
  assert.notEqual(guardedPaper, vanillaPaper)
  command(b, '/modelfixture marked')
  await until('server PDC marker selects guarded item', () => mainHand()?.item.network_id === guardedPaper)
  command(b, '/modelfixture vanilla')
  await until('missing marker keeps vanilla item', () => mainHand()?.item.network_id === vanillaPaper)
  command(b, '/modelfixture marked')
  await until('server PDC marker reapplied', () => mainHand()?.item.network_id === guardedPaper)
  command(b, '/modelfixture wrongmarker')
  await until('different marker keeps vanilla item', () => mainHand()?.item.network_id === vanillaPaper)
  assert(!a.skinUpdates.some(p => p.uuid === remote.uuid), 'remote player must not receive later skin changes')
  await until('individual first-argument homes', () => values(a, 'home').includes('TabTestA_base') && values(b, 'home').includes('TabTestB_base'))
  assert.equal(values(a, 'home').length, 5)
  assert.equal(values(b, 'home').length, 5)
  assert(!values(a, 'home').includes('TabTestB_base'))
  assert(!values(b, 'home').includes('TabTestA_base'))
  await until('nested delete options', () => values(a, 'delhome', ['delete']).includes('TabTestA_base') && values(b, 'delhome', ['delete']).includes('TabTestB_base'))
  assert(!values(b, 'delhome', ['delete']).includes('TabTestA_base'))
  await until('async plugin event results', () => values(a, 'warp').includes('TabTestA_async') && values(b, 'warp').includes('TabTestB_async'))
  assert(!values(a, 'warp').includes('TabTestB_async'))
  await until('sync plugin event results', () => values(a, 'tpa').includes('TabTestA_sync') && values(b, 'tpa').includes('TabTestB_sync'))
  assert(!values(b, 'tpa').includes('TabTestA_sync'))
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
    assert(![...state.enums.values()].some(list => list.includes('secret_value') || list.includes('blocked_secret')), 'Unauthorized or cancelled completion exposed')
  }
  console.log('PASS player isolation, permissions and dynamic updates')
}
main().then(() => { process.exitCode = 0 }).catch(e => { console.error(e); process.exitCode = 1 })
  .finally(async () => { for (const state of clients) { state.closing = true; state.client.close() }; await pause(500); process.exit(process.exitCode) })
