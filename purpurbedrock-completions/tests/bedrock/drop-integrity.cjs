// Exercise normal server item merges in a disposable world; not a region-plugin test.
const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const bedrock = require('bedrock-protocol')
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
let spawned = false, closing = false, error, report
const client = bedrock.createClient({ host: '127.0.0.1', port: 19132,
  version: '1.26.51', username: 'InvTest', offline: true, transport: 'raknet', conLog: () => {} })
client.prependOnceListener('session', profile => { profile.xuid = '30101' })
client.on('error', e => { error = e })
client.on('disconnect', p => { if (!closing) error = new Error('disconnected: ' + JSON.stringify(p)) })
client.on('spawn', () => { spawned = true; client.queue('request_chunk_radius', { chunk_radius: 4, max_radius: 4 }) })
client.on('text', p => { if (typeof p.message === 'string' && p.message.includes('DROPTEST')) report = p.message })
async function until(label, f, timeout = 60000) {
  const end = Date.now() + timeout
  while (Date.now() < end) { if (error) throw error; if (f()) return; await pause(50) }
  throw new Error('timeout: ' + label)
}
async function main() {
  await until('spawn', () => spawned)
  await pause(2000)
  client.queue('command_request', { command: '/integrityfixture drops',
    origin: { type: 'player', uuid: randomUUID(), request_id: '', player_entity_id: 0n }, internal: false, version: '' })
  await until('drop merge report', () => report)
  console.log(report)
  assert(!report.includes('FAIL'), 'drop merge invariant failed')
  for (const key of ['merge', 'partial', 'different-a', 'different-b', 'deny', 'wall', 'nonblock'])
    assert(report.includes(key + '=PASS'), 'missing check ' + key)
  const count = report.match(/cancelEvents=(\d+)/)
  assert(count && Number(count[1]) > 0, 'cancelled merge event did not fire')
  console.log('PASS drop quantities, metadata, wall and cancelled-merge event')
}
main().then(() => { process.exitCode = 0 }).catch(e => { console.error(e); process.exitCode = 1 })
  .finally(async () => { closing = true; client.close(); await pause(300); process.exit(process.exitCode) })
