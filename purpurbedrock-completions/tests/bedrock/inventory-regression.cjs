// Test a real server with a synthetic Bedrock client. This is not a UI test.
const assert = require('node:assert/strict')
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
  client.queue('interact', { action_id: 'open_inventory', target_entity_id: 0n, has_position: false })
}
async function main() {
  await until('player spawned', () => spawned, 90000)
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
  console.log('PASS player inventory open / quick close / reopen')
}
main().then(() => { process.exitCode = 0 }).catch(e => { console.error(e); process.exitCode = 1 })
  .finally(async () => { client.close(); await pause(300); process.exit(process.exitCode) })
