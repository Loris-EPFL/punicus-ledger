#!/usr/bin/env node
// Recompiles every contract scripts/verified-abis/vendor/ holds, and writes what the recompile alone vouches
// for (docs/clear-signing.md, *Phase C*):
//
//     cd scripts/verified-abis && npm ci --ignore-scripts && node verify.mjs            # writes verified.json
//     cd scripts/verified-abis && npm ci --ignore-scripts && node verify.mjs --check    # fails if it differs
//
// Each vendored file is read by its sha256 and checked against it. Each compiler is the linux-amd64 build
// vendor/solc.json pins, fetched from binaries.soliditylang.org into $SOLC_CACHE (default .solc/) and checked
// against its pinned sha256 before it runs; it is run directly, never through a library that might fetch
// another. lib-sourcify builds the compiler input from the metadata's settings and the sources, recompiles, and
// matches the vendored on-chain runtime code, handed to it as the chain's: no RPC is asked. Anything but an exact
// ("perfect") match fails. The functions and their names come from the recompile's own output (the compiler's
// ABI), never from the vendored metadata's or Sourcify's; the contract's name from its compilation target.
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { SolidityMetadataContract, SourcifyChain, Verification, setLibSourcifyLoggerLevel } from '@ethereum-sourcify/lib-sourcify';
import { Interface, keccak256 } from 'ethers';

const here = path.dirname(fileURLToPath(import.meta.url));
const vendor = path.join(here, 'vendor');
const cache = process.env.SOLC_CACHE ?? path.join(here, '.solc');
const check = process.argv.includes('--check');
setLibSourcifyLoggerLevel(0);

const sha256 = (data) => createHash('sha256').update(data).digest('hex');

// Nothing but the pinned compilers is fetched: lib-sourcify would fetch a source it finds missing from IPFS or
// GitHub, and that must fail, never pass on a file the reviewer did not read.
const realFetch = globalThis.fetch;
globalThis.fetch = (url, ...rest) => {
  const host = new URL(typeof url === 'string' ? url : url.url).host;
  if (host !== 'binaries.soliditylang.org') throw new Error(`refused: a fetch from ${host}; only the compilers are fetched`);
  return realFetch(url, ...rest);
};

/** The vendored file [digest] names, refused unless it hashes to it. */
function blob(digest) {
  const data = fs.readFileSync(path.join(vendor, 'blobs', digest));
  if (sha256(data) !== digest) throw new Error(`vendored file ${digest} does not hash to its name`);
  return data;
}

/** The pinned compiler [version], fetched once and checked against its pinned sha256 before every use. */
async function compiler(version) {
  const pin = JSON.parse(fs.readFileSync(path.join(vendor, 'solc.json'), 'utf8'))[version];
  if (!pin) throw new Error(`solc ${version} is not pinned in vendor/solc.json`);
  const file = path.join(cache, pin.path);
  if (!fs.existsSync(file)) {
    fs.mkdirSync(cache, { recursive: true });
    const response = await fetch(`https://binaries.soliditylang.org/linux-amd64/${pin.path}`);
    if (!response.ok) throw new Error(`solc ${version}: HTTP ${response.status}`);
    fs.writeFileSync(file, Buffer.from(await response.arrayBuffer()), { mode: 0o755 });
  }
  if (sha256(fs.readFileSync(file)) !== pin.sha256) {
    fs.rmSync(file);
    throw new Error(`solc ${version} does not hash to its pin`);
  }
  return file;
}

/** lib-sourcify's compiler: the pinned binary, run with --standard-json, and nothing else. */
const solc = {
  async compile(version, input) {
    const run = spawnSync(await compiler(version), ['--standard-json'], {
      input: JSON.stringify(input),
      maxBuffer: 1 << 30,
    });
    if (run.status !== 0) throw new Error(`solc ${version} failed: ${run.stderr}`);
    return JSON.parse(run.stdout.toString('utf8'));
  },
};

/** The canonical type of an ABI input, a tuple's spelled out. */
function canonical(input) {
  if (!input.type.startsWith('tuple')) return input.type;
  return `(${input.components.map(canonical).join(',')})${input.type.slice('tuple'.length)}`;
}

/** An ABI input as the catalog keeps it: its name, its canonical type, a tuple's members. */
function parameter(input) {
  const out = { name: input.name, type: canonical(input) };
  if (input.components) out.components = input.components.map(parameter);
  return out;
}

// The list, and each contract's code hash as check_code_hashes.py pinned it from the chain.
const listedPins = new Map(
  JSON.parse(fs.readFileSync(path.join(here, 'contracts.json'), 'utf8')).contracts
    .map((c) => [`${c.chainId}-${c.address.toLowerCase()}`, c.codeHash]),
);
const verified = [];
for (const manifestFile of fs.readdirSync(path.join(vendor, 'contracts')).sort()) {
  const manifest = JSON.parse(fs.readFileSync(path.join(vendor, 'contracts', manifestFile), 'utf8'));
  const metadata = JSON.parse(blob(manifest.metadata).toString('utf8'));
  const sources = Object.entries(manifest.sources).map(([p, digest]) => ({ path: p, content: blob(digest).toString('utf8') }));
  const runtime = blob(manifest.runtime).toString('ascii');
  // Exactly the metadata's sources, each the one it names by its keccak256, before anything is compiled.
  const paths = Object.keys(metadata.sources).sort();
  if (JSON.stringify(paths) !== JSON.stringify(Object.keys(manifest.sources).sort())) {
    throw new Error(`${manifestFile}: the vendored sources are not exactly the metadata's`);
  }
  const contract = new SolidityMetadataContract(metadata, sources);
  if (Object.keys(contract.missingSources).length || Object.keys(contract.invalidSources).length ||
      contract.unusedSourceFiles.length) {
    throw new Error(`${manifestFile}: a source is missing, invalid or unused`);
  }
  for (const [p, entry] of Object.entries(metadata.sources)) {
    if (entry.content !== undefined) throw new Error(`${manifestFile}: ${p} is inline in the metadata, not vendored`);
  }
  const compilation = await contract.createCompilation(solc);
  // No provider: the chain's code is the vendored one, and nothing else is asked of it.
  const chain = new SourcifyChain({ name: 'vendored', chainId: manifest.chainId, rpcs: [], supported: false });
  chain.getBytecode = async () => runtime;
  const verification = new Verification(compilation, chain, manifest.address);
  await verification.verify();
  if (verification.status.runtimeMatch !== 'perfect') {
    throw new Error(`${manifest.chainId}-${manifest.address}: ${verification.status.runtimeMatch}, not an exact match`);
  }
  // What matched: lib-sourcify may swap in a recompile of source variations to find the matched metadata.
  const matched = verification.compilation;
  if (!listedPins.has(`${manifest.chainId}-${manifest.address}`)) {
    throw new Error(`${manifestFile}: not on contracts.json`);
  }
  const pinned = listedPins.get(`${manifest.chainId}-${manifest.address}`);
  if (pinned !== keccak256(runtime)) {
    throw new Error(`${manifestFile}: the code hashes to ${keccak256(runtime)}, not to the pinned ${pinned}`);
  }
  const abi = matched.contractCompilerOutput.abi;
  const iface = new Interface(abi);
  const functions = [];
  iface.forEachFunction((fragment) => {
    const entry = abi.find((e) => e.type === 'function' && e.name === fragment.name &&
      e.inputs.map(canonical).join(',') === fragment.inputs.map((i) => i.format('sighash')).join(','));
    functions.push({ selector: fragment.selector, name: fragment.name, inputs: entry.inputs.map(parameter) });
  });
  // ethers skips a fragment it cannot read, and a repeated signature, without an error: nothing may drop out.
  if (functions.length !== abi.filter((e) => e.type === 'function').length) {
    throw new Error(`${manifestFile}: ${abi.filter((e) => e.type === 'function').length} functions compiled, ${functions.length} read`);
  }
  functions.sort((a, b) => (a.selector < b.selector ? -1 : a.selector > b.selector ? 1 : 0));
  verified.push({
    chainId: manifest.chainId,
    address: manifest.address,
    name: matched.compilationTarget.name,
    codeHash: keccak256(runtime),
    functions,
  });
  console.error(`${manifest.chainId} ${manifest.address} ${compilation.compilationTarget.name}: exact match`);
}

const seen = new Set(verified.map((v) => `${v.chainId}-${v.address}`));
for (const key of listedPins.keys()) if (!seen.has(key)) throw new Error(`${key}: on contracts.json, not vendored`);
const out = JSON.stringify(verified, null, 2) + '\n';
const target = path.join(here, 'verified.json');
if (check) {
  if (!fs.existsSync(target) || fs.readFileSync(target, 'utf8') !== out) {
    console.error('verified.json is not what the recompile gives: run node verify.mjs, and review the diff');
    process.exit(1);
  }
} else {
  fs.writeFileSync(target, out);
}
