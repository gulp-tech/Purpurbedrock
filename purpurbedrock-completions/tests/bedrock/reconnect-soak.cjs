// Disposable isolated-server test only. This does not simulate a mobile network.
const assert = require('node:assert/strict')
const bedrock = require('bedrock-protocol')
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const version = process.env.TEST_VERSION || '1.26.51'
const phase = process.env.TEST_PHASE || 'A'
const count = Number(process.env.TEST_COUNT || '6')
assert(/^[AB]$/.test(phase) && Number.isInteger(count) && count > 0 && count <= 20)
async function connect(index) {
  // Reuse the same identity within each phase to exercise reconnect cleanup.
  const username = `Soak${phase}`
  const xuid = phase === 'A' ? '40000' : '50000'
  let client
  let timer
  try {
    await new Promise((resolve, reject) => {
      client = bedrock.createClient({ host: '127.0.0.1', port: 19132, version,
        username, offline: true, transport: 'raknet', conLog: () => {} })
      client.prependOnceListener('session', profile => { profile.xuid = xuid })
      timer = setTimeout(() => reject(new Error(`spawn timed out for ${username}`)), 60000)
      client.once('error', reject)
      client.once('disconnect', p => reject(new Error(`disconnect before spawn: ${JSON.stringify(p)}`)))
      client.once('spawn', () => {
        client.queue('request_chunk_radius', { chunk_radius: 4, max_radius: 4 })
        resolve()
      })
    })
    clearTimeout(timer)
    await pause(750)
    console.log(`SPAWNED ${username} ${version} attempt=${index + 1}`)
  } finally {
    clearTimeout(timer)
    if (client) client.close()
    await pause(1250)
  }
}
async function main() {
  for (let i = 0; i < count; i++) await connect(i)
  console.log(`PHASE_PASS ${phase} ${version} ${count}`)
}
main().catch(error => { console.error(error); process.exitCode = 1 }).finally(() => process.exit(process.exitCode || 0))
