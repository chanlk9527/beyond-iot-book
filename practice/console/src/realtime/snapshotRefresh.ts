/** Throttle bursts without starving continuous reports; at most one read is in flight. */
export function snapshotRefresh(read:()=>Promise<void>,delay=150) {
  let timer:ReturnType<typeof setTimeout>|undefined, running=false, queued=false, disposed=false
  function schedule(){
    if(disposed)return
    queued=true
    if(!running&&!timer)timer=setTimeout(()=>void run(),delay)
  }
  async function run(){
    timer=undefined;queued=false;running=true
    try{await read()}finally{running=false;if(queued&&!disposed)schedule()}
  }
  function cancel(){if(timer)clearTimeout(timer);timer=undefined;queued=false}
  return {schedule,cancel,dispose(){disposed=true;cancel()}}
}
