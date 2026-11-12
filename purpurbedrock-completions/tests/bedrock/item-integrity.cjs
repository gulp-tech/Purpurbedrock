// Isolated server-side item conservation test using a synthetic Bedrock client.
// This intentionally sends a stale stack ID after a normal transfer; no production data is used.
const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const bedrock = require('bedrock-protocol')
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const client = bedrock.createClient({ host: '127.0.0.1', port: 19132,
  version: '1.26.51', username: 'InvTest', offline: true, transport: 'raknet', conLog: () => {} })
let spawned = false, error, closing = false
const opens = [], contents = [], responses = [], messages = []
client.prependOnceListener('session', profile => { profile.xuid = '30101' })
client.on('error', e => { error = e })
client.on('disconnect', p => { if (!closing) error = new Error('disconnected: ' + JSON.stringify(p)) })
client.on('spawn', () => { spawned = true; client.queue('request_chunk_radius', { chunk_radius: 4, max_radius: 4 }) })
client.on('container_open', p => opens.push(p))
client.on('container_close', p => { if (p.server) client.queue('container_close', { window_id: p.window_id, window_type: p.window_type, server: false }) })
client.on('inventory_content', p => contents.push(p))
client.on('item_stack_response', p => responses.push(...p.responses))
client.on('text', p => { if (typeof p.message === 'string' && p.message.includes('INTEGRITY')) messages.push(p.message) })
async function until(label, f, timeout = 15000) {
  const end = Date.now() + timeout
  while (Date.now() < end) { if (error) throw error; const value = f(); if (value) return value; await pause(25) }
  throw new Error('timeout: ' + label)
}
async function command(text) {
  await pause(1200) // Keep the server's normal command-spam protection enabled.
  client.queue('command_request', { command: text,
    origin: { type: 'player', uuid: randomUUID(), request_id: '', player_entity_id: 0n }, internal: false, version: '' })
}
async function check(expected) {
  const before = messages.length
  await command('/integrityfixture check')
  await until('server-side conservation report', () => messages.length > before)
  const text = messages.at(-1)
  console.log(text)
  const found = text.match(/chest=(\d+) inventory=(\d+) cursor=(\d+) ground=(\d+) total=(\d+)/)
  assert(found, 'invalid fixture report: ' + text)
  assert.deepEqual(found.slice(1).map(Number), expected, 'authoritative inventory state mismatch')
}
function request(id, type, count, source, destination) {
  client.queue('item_stack_request', { requests: [{ request_id: id,
    actions: [{ type_id: type, legacy_type_id: 0, count, source, destination }],
    custom_names: [], cause: 'chat_public' }] })
}
const slot = (container_id, n, stack_id) => ({ slot_type: { container_id, dynamic_container_id: undefined }, slot: n, stack_id })
async function main() {
  await until('player spawned', () => spawned && client.entityId, 90000)
  await pause(2000)
  await command('/integrityfixture start')
  await until('fixture chest open', () => opens.length > 0)
  const menu = opens.at(-1)
  const packet = await until('fixture chest contents', () => contents.find(p => p.window_id === menu.window_id && p.input?.[0]?.count === 20))
  const originalStackId = packet.input[0].stack_id
  assert(Number.isInteger(originalStackId) && originalStackId > 0, 'missing original network stack id')
  await check([20, 10, 0, 0, 30])
  request(-101, 'take', 20, slot('container', 0, originalStackId), slot('cursor', 0, 0))
  const take = await until('take response', () => responses.find(r => r.request_id === -101))
  assert.equal(take.status, 'ok')
  const cursor = take.containers.flatMap(c => c.slots).find(s => s.count === 20 && s.item_stack_id > 0)
  assert(cursor, 'missing cursor stack id')
  await check([0, 10, 20, 0, 30])
  request(-103, 'place', 20, slot('cursor', 0, cursor.item_stack_id), slot('hotbar_and_inventory', 10, 0))
  const place = await until('place response', () => responses.find(r => r.request_id === -103))
  assert.equal(place.status, 'ok')
  await check([0, 30, 0, 0, 30])
  // Replay the old item reference after its original slot has been emptied. The server must not mint another stack.
  request(-105, 'take', 20, slot('container', 0, originalStackId), slot('cursor', 0, 0))
  await until('stale request response', () => responses.find(r => r.request_id === -105))
  await check([0, 30, 0, 0, 30])
  const opened = opens.at(-1)
  client.queue('container_close', { window_id: opened.window_id, window_type: opened.window_type, server: false })
  await pause(300)
  await check([0, 30, 0, 0, 30])
  console.log('PASS server-side item conservation, stale replay and menu close')
}
main().then(() => { process.exitCode = 0 }).catch(e => { console.error(e); process.exitCode = 1 })
  .finally(async () => { closing = true; client.close(); await pause(300); process.exit(process.exitCode) })
