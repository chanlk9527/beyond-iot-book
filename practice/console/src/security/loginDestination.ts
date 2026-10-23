/** Only known local destinations can be used after login. */
export function loginDestination(value: unknown): string {
  return value === '/app' ? '/app' : value === '/developer/apps' ? '/developer/apps' : '/overview'
}
