export function isLocalNetworkAddress(remoteAddress: string | undefined): boolean {
  if (remoteAddress === undefined) {
    return false;
  }
  const address = remoteAddress.startsWith('::ffff:') ? remoteAddress.slice(7) : remoteAddress;

  if (address === '::1' || address.startsWith('127.')) {
    return true;
  }
  if (address.startsWith('10.') || address.startsWith('192.168.')) {
    return true;
  }

  const octets = address.split('.');
  if (octets.length === 4 && octets[0] === '172') {
    const second = Number(octets[1]);
    if (Number.isInteger(second) && second >= 16 && second <= 31) {
      return true;
    }
  }

  const lower = address.toLowerCase();
  if (lower.startsWith('fe80:') || /^f[cd][0-9a-f]{2}:/.test(lower)) {
    return true;
  }

  return false;
}
