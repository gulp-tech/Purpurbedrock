// Test a real server with a synthetic Bedrock client. This is not a UI test.
const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const bedrock = require('bedrock-protocol')
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
let error
let spawned = false
const opens = []
const closes = []
const contents = []
const slots = []
const replies = []
const items = new Map()
const menuReports = []
const client = bedrock.createClient({ host: '127.0.0.1', port: 19132,
  version: '1.26.51', username: 'InvTest', offline: true, transport: 'raknet', conLog: () => {} })
client.prependOnceListener('session', profile => { profile.xuid = '30101' })
client.on('error', e => { error = e })
client.on('disconnect', p => { error = new Error('disconnected: ' + JSON.stringify(p)) })
client.on('spawn', () => { spawned = true; client.queue('request_chunk_radius', { chunk_radius: 4, max_radius: 4 }) })
client.on('container_open', packet => { opens.push(packet); console.log('container_open', JSON.stringify(packet, (_, v) => typeof v === 'bigint' ? v.toString() : v)) })
client.on('container_close', packet => {
  closes.push(packet)
  console.log('container_close', JSON.stringify(packet))
  if (packet.server) client.queue('container_close', { window_id: packet.window_id, window_type: packet.window_type, server: false })
})
client.on('item_registry', packet => { for (const item of packet.itemstates) items.set(item.name, item.runtime_id) })
client.on('inventory_content', packet => contents.push(packet))
client.on('inventory_slot', packet => slots.push(packet))
client.on('item_stack_response', packet => replies.push(packet))
client.on('text', packet => { if (typeof packet.message === 'string' && packet.message.includes('MENU_INTEGRITY')) menuReports.push(packet.message) })
async function until(label, f, timeout = 15000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (error) throw error
    if (f()) return
    await pause(25)
  }
  throw new Error('timeout: ' + label)
}
function click(slot, requestId) {
  client.queue('item_stack_request', { requests: [{ request_id: requestId, actions: [{
    type_id: 'take', legacy_type_id: 0, count: 1,
    source: { slot_type: { container_id: 'container', dynamic_container_id: undefined }, slot, stack_id: -1 },
    destination: { slot_type: { container_id: 'cursor', dynamic_container_id: undefined }, slot: 0, stack_id: 0 }
  }], custom_names: [], cause: 'chat_public' }] })
}
function openPlayerInventory() {
  client.queue('interact', { action_id: 'open_inventory', target_entity_id: client.entityId, has_position: false })
}
async function main() {
  await until('player spawned', () => spawned && client.entityId, 90000)
  await pause(2000) // Allow the initialization packet to be handled before interacting.
  openPlayerInventory()
  await until('player inventory open', () => opens.length >= 1)
  assert.equal(opens[0].window_id, 'inventory')
  assert.equal(opens[0].window_type, 'inventory')
  // A valid quick player-initiated close must not be mistaken for a stale menu close.
  client.queue('container_close', { window_id: opens[0].window_id, window_type: opens[0].window_type, server: false })
  await until('close confirmation or unwanted reopen', () => closes.length > 0 || opens.length > 1)
  await pause(200)
  assert.equal(opens.length, 1, 'quick player close unexpectedly reopens inventory')
  assert.equal(closes.length, 1, 'player close must be acknowledged')
  openPlayerInventory()
  await until('player inventory reopens on next request', () => opens.length >= 2)
  await pause(1000)
  client.queue('container_close', { window_id: opens[1].window_id, window_type: opens[1].window_type, server: false })
  await until('delayed close acknowledged', () => closes.length >= 2)
  openPlayerInventory()
  await until('player inventory opens after delayed close', () => opens.length >= 3)
  client.queue('container_close', { window_id: opens[2].window_id, window_type: opens[2].window_type, server: false })
  await until('second quick close acknowledged', () => closes.length >= 3)
  console.log('PASS player inventory open / quick and delayed close / reopen')

  // The isolated server grants only this test user operator access to its built-in menu.
  // This checks packets, not actual rendering or DeluxeMenus behaviour.
  await pause(1200)
  client.queue('command_request', { command: '/purpurbedrock menu',
    origin: { type: 'player', uuid: randomUUID(), request_id: '', player_entity_id: 0n }, internal: false, version: '' })
  await until('built-in menu opened', () => opens.length >= 4)
  await until('menu items registered', () => items.has('minecraft:diamond_ore') && items.has('minecraft:arrow'))
  const menu = opens[3]
  assert.equal(menu.window_type, 'container')
  await until('menu contents received', () => contents.some(p => p.window_id === menu.window_id && p.input?.[14]?.network_id === items.get('minecraft:diamond_ore')))
  const countBeforeSubmenu = contents.length
  click(14, -11) // Built-in menu: open the anti-xray submenu.
  await until('submenu response', () => replies.length > 0, 15000)
  console.log('first click response', JSON.stringify(replies.at(-1)))
  await until('submenu contents received', () => contents.slice(countBeforeSubmenu).some(p => p.window_id === opens.at(-1).window_id && p.input?.[22]?.network_id === items.get('minecraft:arrow')), 15000)
  const countBeforeBack = contents.length
  click(22, -13) // Return from submenu to the main menu.
  await until('second submenu click response', () => replies.length > 1, 15000)
  console.log('second click response', JSON.stringify(replies.at(-1)))
  await until('main menu contents received after transition', () => contents.slice(countBeforeBack).some(p => p.window_id === opens.at(-1).window_id && p.input?.[14]?.network_id === items.get('minecraft:diamond_ore')), 15000)
  const active = opens.at(-1)
  const priorCloses = closes.length
  const priorOpens = opens.length
  client.queue('container_close', { window_id: active.window_id, window_type: active.window_type, server: false })
  await until('built-in menu close acknowledged or unwanted reopen', () => closes.length > priorCloses || opens.length > priorOpens)
  await pause(200)
  assert.equal(opens.length, priorOpens, 'quick menu close unexpectedly reopens menu')
  assert(closes.length > priorCloses, 'quick menu close must be acknowledged')
  const previousOpens = opens.length
  openPlayerInventory()
  await until('player inventory opens after menu', () => opens.length > previousOpens)
  assert.equal(opens.at(-1).window_type, 'inventory')
  console.log('PASS built-in menu opens and returns to player inventory')
  const inventory = opens.at(-1)
  const closeCount = closes.length
  client.queue('container_close', { window_id: inventory.window_id, window_type: inventory.window_type, server: false })
  await until('close player inventory before different-size menu chain', () => closes.length > closeCount)
  await pause(1200)
  client.queue('command_request', { command: '/inventoryfixture',
    origin: { type: 'player', uuid: randomUUID(), request_id: '', player_entity_id: 0n }, internal: false, version: '' })
  await until('fixture first menu', () => opens.length > previousOpens + 1)
  for (const [index, itemName] of ['diamond', 'emerald', 'gold_ingot'].entries()) {
    const itemId = items.get('minecraft:' + itemName)
    assert.notEqual(itemId, undefined, 'missing item registry: ' + itemName)
    await until('fixture page ' + index + ' contents', () => {
      const open = opens.at(-1)
      return contents.some(packet => packet.window_id === open.window_id && packet.input?.[0]?.network_id === itemId)
    })
    if (index < 2) {
      const oldOpens = opens.length
      click(0, -21 - index * 2)
      await until('fixture next page opens ' + index, () => opens.length > oldOpens)
    }
  }
  const final = opens.at(-1)
  const prior = closes.length
  const beforeFinalClose = opens.length
  client.queue('container_close', { window_id: final.window_id, window_type: final.window_type, server: false })
  await until('fixture final page closes', () => closes.length > prior || opens.length > beforeFinalClose)
  assert.equal(opens.length, beforeFinalClose, 'closing fixture page reopened it')
  const beforeFinalInventory = opens.length
  openPlayerInventory()
  await until('player inventory opens after different-size transitions', () => opens.length > beforeFinalInventory)
  assert.equal(opens.at(-1).window_type, 'inventory')
  console.log('PASS 27 -> 54 -> 9 slot transitions and player inventory recovery')
  await pause(1200)
  client.queue('command_request', { command: '/integrityfixture menucheck',
    origin: { type: 'player', uuid: randomUUID(), request_id: '', player_entity_id: 0n }, internal: false, version: '' })
  await until('authoritative cancelled menu item report', () => menuReports.length > 0)
  console.log(menuReports.at(-1))
  assert(menuReports.at(-1).includes('owned=0 ground=0'), 'cancelled menu clicks leaked icons')

  // Java may send a delayed close for the previous window after a same-size
  // submenu has opened. The new menu must remain usable, not lose its holder.
  const playerInventory = opens.at(-1)
  const prevClose = closes.length
  client.queue('container_close', { window_id: playerInventory.window_id, window_type: playerInventory.window_type, server: false })
  await until('player inventory closed before stale menu test', () => closes.length > prevClose)
  await pause(1200)
  client.queue('command_request', { command: '/inventoryfixture stale',
    origin: { type: 'player', uuid: randomUUID(), request_id: '', player_entity_id: 0n }, internal: false, version: '' })
  const initialContents = contents.length
  await until('stale fixture first page', () => contents.slice(initialContents).some(p => p.input?.[0]?.network_id === items.get('minecraft:diamond')))
  const beforeTransition = contents.length
  click(0, -51)
  await until('stale fixture second page', () => contents.slice(beforeTransition).some(p => p.input?.[0]?.network_id === items.get('minecraft:emerald')))
  await pause(1000) // Fixture sends the previous Java window's stale close after 5 ticks.
  const beforeThird = contents.length
  click(0, -53)
  await until('stale fixture third page remains clickable', () => contents.slice(beforeThird).some(p => p.input?.[0]?.network_id === items.get('minecraft:gold_ingot')))
  console.log('PASS stale Java close does not break the next submenu')
}
main().then(() => { process.exitCode = 0 }).catch(e => { console.error(e); process.exitCode = 1 })
  .finally(async () => { client.close(); await pause(300); process.exit(process.exitCode) })
