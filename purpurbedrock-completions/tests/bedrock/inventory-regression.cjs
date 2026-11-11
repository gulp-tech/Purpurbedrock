// Test a real server with a synthetic Bedrock client. This is not a UI test.
const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const bedrock = require('bedrock-protocol')
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
let error
let spawned = false
const opens = []
const closes = []
const client = bedrock.createClient({ host: '127.0.0.1', port: 19132,
  version: '1.26.51', username: 'InvTest', offline: true, transport: 'raknet', conLog: () => {} })
client.prependOnceListener('session', profile => { profile.xuid = '30101' })
client.on('error', e => { error = e })
client.on('disconnect', p => { error = new Error('disconnected: ' + JSON.stringify(p)) })
client.on('spawn', () => { spawned = true; client.queue('request_chunk_radius', { chunk_radius: 4, max_radius: 4 }) })
client.on('container_open', packet => { opens.push(packet); console.log('container_open', JSON.stringify(packet, (_, v) => typeof v === 'bigint' ? v.toString() : v)) })
client.on('container_close', packet => { closes.push(packet); console.log('container_close', JSON.stringify(packet)) })
async function until(label, f, timeout = 15000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (error) throw error
    if (f()) return
    await pause(25)
  }
  throw new Error('timeout: ' + label)
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
  const menu = opens[3]
  assert.equal(menu.window_type, 'container')
  await pause(1000) // Test normal close separately from the existing submenu guard.
  const priorCloses = closes.length
  client.queue('container_close', { window_id: menu.window_id, window_type: menu.window_type, server: false })
  await until('built-in menu close acknowledged', () => closes.length > priorCloses)
  const previousOpens = opens.length
  openPlayerInventory()
  await until('player inventory opens after menu', () => opens.length > previousOpens)
  assert.equal(opens.at(-1).window_type, 'inventory')
  console.log('PASS built-in menu opens and returns to player inventory')
}
main().then(() => { process.exitCode = 0 }).catch(e => { console.error(e); process.exitCode = 1 })
  .finally(async () => { client.close(); await pause(300); process.exit(process.exitCode) })
