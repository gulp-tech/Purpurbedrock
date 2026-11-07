const { createHash } = require('node:crypto')
const bedrock = require('bedrock-protocol')
const pause = ms => new Promise(r => setTimeout(r, ms))
const states = []
function enumName(label) { return `${label}_arg1_${createHash('sha256').update(label + '\0S').digest('hex').slice(0, 16)}` }
function connect(username, xuid) {
  const s = { username, enums: new Map(), packets: [], spawned: false }; states.push(s)
  const c = s.client = bedrock.createClient({ host: '127.0.0.1', port: 19132, version: '1.26.51', username, offline: true, transport: 'raknet', conLog: () => {} })
  c.prependOnceListener('session', p => { p.xuid = xuid })
  c.on('error', e => { s.error = String(e) }); c.on('disconnect', e => { if (!s.closing) s.error = JSON.stringify(e) })
  c.on('spawn', () => { s.spawned = true; c.queue('request_chunk_radius', { chunk_radius: 4, max_radius: 4 }) })
  c.on('available_commands', packet => { s.packets.push(packet); s.latest = packet; s.enums = new Map(packet.dynamic_enums.map(e => [e.name, e.values])) })
  c.on('update_soft_enum', p => { const old = s.enums.get(p.enum_type) || []; s.enums.set(p.enum_type, p.action_type === 'update' ? p.options : p.action_type === 'add' ? [...new Set([...old, ...p.options])] : old.filter(v => !p.options.includes(v))) })
  return s
}
function root(s, label) { return s.latest?.command_data.find(d => d.name === label || (s.latest.enums[d.alias]?.values || []).some(i => s.latest.enum_values[i] === label)) }
function allChoices(s, label) {
  const definition = root(s, label); if (!definition) return []
  return definition.overloads.flatMap(o => o.parameters).flatMap(p => {
    if (p.enum_type === 'soft_enum') return s.enums.get(s.latest.dynamic_enums[p.value_type]?.name) || []
    if (p.enum_type === 'enum') return (s.latest.enums[p.value_type]?.values || []).map(i => s.latest.enum_values[i])
    return []
  })
}
async function main() {
  const a = connect('TabTestA', '10001'), b = connect('TabTestB', '10002')
  for (let i = 0; i < 900; i++) {
    if (a.error || b.error) throw Error(`${a.error || ''} ${b.error || ''}`)
    if (a.spawned && b.spawned && a.enums.get(enumName('home'))?.includes('TabTestA_home')) break
    await pause(100)
  }
  await pause(30000)
  if (!a.spawned || !b.spawned) throw Error('Synthetic clients did not spawn')
  const checks = {
    home: Boolean(a.enums.get(enumName('home'))?.includes('TabTestA_home')),
    async_plugin: Boolean(a.enums.get(enumName('warp'))?.includes('TabTestA_async')),
    typed_paper: allChoices(a, 'wild').includes('TabTestA_typed'),
    vanilla_gamemode: ['survival', 'creative'].every(x => allChoices(a, 'gamemode').includes(x)),
    vanilla_damage: allChoices(a, 'damage').some(x => /(^|:)fall$/.test(x)),
  }
  for (const [key, value] of Object.entries(checks)) console.log(`::notice title=Completion coverage::${key}=${value}`)
  for (const label of ['home', 'warp', 'wild', 'gamemode', 'damage', 'give']) {
    const def = root(a, label)
    console.log(`::notice title=Advertised command::${label}=${Boolean(def)} args=${def?.overloads.length || 0} choices=${allChoices(a, label).length}`)
  }
  for (const [key, value] of Object.entries(checks)) if (!value) throw Error('Missing expected completion: ' + key)
}
main().catch(e => { console.error(e); process.exitCode = 1 }).finally(async () => { for (const s of states) { s.closing = true; s.client.close() }; await pause(300); process.exit(process.exitCode || 0) })
