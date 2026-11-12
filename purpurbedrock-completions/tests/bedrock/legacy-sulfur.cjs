// Disposable packet test, not an official graphical Bedrock client.
const assert = require('node:assert/strict')
const bedrock = require('bedrock-protocol')
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const version = process.env.TEST_VERSION
const expectFailure = process.env.EXPECT_FAILURE === 'true'
const old = version === '1.21.90'
const client = bedrock.createClient({ host: '127.0.0.1', port: 19132, version,
  username: 'LegacyTest', offline: true, transport: 'raknet', conLog: () => {} })
client.prependOnceListener('session', profile => { profile.xuid = '30133' })
let spawned = false, error, disconnected = false
const entities = [], sounds = []
client.on('error', e => { error = e; console.error('CLIENT_ERROR', e.message) })
client.on('disconnect', p => { disconnected = true; console.log('DISCONNECT', JSON.stringify(p)) })
client.on('spawn', () => { spawned = true; client.queue('request_chunk_radius', { chunk_radius: 4, max_radius: 4 }) })
const show = p => JSON.stringify(p, (_, v) => typeof v === 'bigint' ? v.toString() : v)
client.on('add_entity', p => { entities.push(p.identifier || p.entity_type); if (entities.length <= 3) console.log('ENTITY', show(p)) })
client.on('level_sound_event', p => { sounds.push(p.sound || p.sound_id); if (sounds.length <= 3) console.log('SOUND', show(p)) })
async function main() {
  for (let i = 0; i < 900 && !spawned && !error && !disconnected; i++) await pause(100)
  assert(spawned, 'client never spawned: ' + (error?.message || ''))
  for (let i = 0; i < 200 && !error && !disconnected; i++) await pause(100)
  console.log('RESULT', JSON.stringify({version, expectFailure, spawned, disconnected, error: error?.message, entities, sounds}))
  if (expectFailure) {
    // Some synthetic transports do not surface the server close. The workflow
    // independently requires the exact ABSORB_BLOCK server exception as control.
    console.log('CONTROL server log must contain the expected serializer failure')
  } else {
    assert(!error && !disconnected, 'client was disconnected or failed')
    assert(entities.includes(old ? 'minecraft:slime' : 'minecraft:sulfur_cube'), 'expected mob representation missing')
    if (old) assert(sounds.filter(s => String(s).toLowerCase().includes('squish')).length >= 2, 'missing substitute sounds')
    else {
      assert(sounds.some(s => String(s).toLowerCase().includes('absorb_block')), 'missing native absorb sound')
      assert(sounds.some(s => String(s).toLowerCase().includes('eject_block')), 'missing native eject sound')
    }
  }
}
main().then(() => { process.exitCode = 0 }).catch(e => { console.error(e); process.exitCode = 1 })
  .finally(async () => { client.close(); await pause(300); process.exit(process.exitCode) })
