/** Only known local destinations can be used after login. */
export function loginDestination(value: unknown): string {
  return value === '/developer/apps' ? '/developer/apps' : '/overview'
}
