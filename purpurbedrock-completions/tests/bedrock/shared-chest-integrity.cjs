// Two isolated players simultaneously request the same marked chest stack.
const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const bedrock = require('bedrock-protocol')
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const states = []
function connect(username, xuid) {
  const state = { spawned: false, opens: [], contents: [], responses: [], texts: [], closing: false }
  const c = state.client = bedrock.createClient({ host: '127.0.0.1', port: 19132,
    version: '1.26.51', username, offline: true, transport: 'raknet', conLog: () => {} })
  c.prependOnceListener('session', profile => { profile.xuid = xuid })
  c.on('error', e => { state.error = e })
  c.on('disconnect', p => { if (!state.closing) state.error = new Error(JSON.stringify(p)) })
  c.on('spawn', () => { state.spawned = true; c.queue('request_chunk_radius', { chunk_radius: 4, max_radius: 4 }) })
  c.on('container_open', p => state.opens.push(p))
  c.on('container_close', p => { if (p.server) c.queue('container_close', { window_id: p.window_id, window_type: p.window_type, server: false }) })
  c.on('inventory_content', p => state.contents.push(p))
  c.on('item_stack_response', p => state.responses.push(...p.responses))
  c.on('text', p => { if (typeof p.message === 'string' && p.message.includes('SHARED')) state.texts.push(p.message) })
  states.push(state)
  return state
}
async function until(label, test, timeout = 90000) {
  const end = Date.now() + timeout
  while (Date.now() < end) { for (const s of states) if (s.error) throw s.error; const v = test(); if (v) return v; await pause(25) }
  throw new Error('timeout: ' + label)
}
function command(s, text) { s.client.queue('command_request', { command: text,
  origin: { type: 'player', uuid: randomUUID(), request_id: '', player_entity_id: 0n }, internal: false, version: '' }) }
function take(s, id, stackId) {
  const slot = (container_id, n, stack_id) => ({ slot_type: { container_id, dynamic_container_id: undefined }, slot: n, stack_id })
  s.client.queue('item_stack_request', { requests: [{ request_id: id, actions: [{ type_id: 'take', legacy_type_id: 0, count: 20,
    source: slot('container', 0, stackId), destination: slot('cursor', 0, 0) }], custom_names: [], cause: 'chat_public' }] })
}
async function main() {
  const a = connect('InvTest', '30101'), b = connect('InvTestB', '30102')
  await until('both clients spawned', () => a.spawned && b.spawned)
  await pause(2500)
  command(a, '/integrityfixture start')
  await until('first chest opened', () => a.opens.length > 0)
  const ca = await until('first chest contents', () => a.contents.find(p => p.window_id === a.opens.at(-1).window_id && p.input?.[0]?.count === 20))
  command(b, '/integrityfixture join')
  await until('second chest opened', () => b.opens.length > 0)
  const cb = await until('second chest contents', () => b.contents.find(p => p.window_id === b.opens.at(-1).window_id && p.input?.[0]?.count === 20))
  const ida = ca.input[0].stack_id, idb = cb.input[0].stack_id
  assert(Number.isInteger(ida) && ida > 0 && Number.isInteger(idb) && idb > 0)
  take(a, -201, ida)
  take(b, -203, idb)
  await until('both requests responded', () => a.responses.some(r => r.request_id === -201) && b.responses.some(r => r.request_id === -203))
  await pause(1200)
  command(a, '/integrityfixture shared')
  await until('shared server-side report', () => a.texts.length > 0)
  console.log(a.texts.at(-1))
  const counts = a.texts.at(-1).match(/chest=(\d+) a=(\d+) b=(\d+) ground=(\d+) total=(\d+)/)
  assert(counts, 'invalid shared report')
  assert.equal(Number(counts[5]), 30, 'simultaneous transfers duplicated or lost items')
  assert.equal(Number(counts[4]), 0, 'unexpected dropped test items')
  console.log('PASS simultaneous shared container request, total=30')
}
main().then(() => { process.exitCode = 0 }).catch(e => { console.error(e); process.exitCode = 1 })
  .finally(async () => { for (const s of states) { s.closing = true; s.client.close() }; await pause(300); process.exit(process.exitCode) })
