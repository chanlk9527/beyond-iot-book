import { expect,it,vi } from 'vitest'
import { snapshotRefresh } from './snapshotRefresh'
it('bounds continuous updates to one in-flight read and retains a trailing refresh',async()=>{
 vi.useFakeTimers()
 let finish!:()=>void
 const read=vi.fn().mockImplementationOnce(()=>new Promise<void>(resolve=>{finish=resolve})).mockResolvedValue(undefined)
 const refresh=snapshotRefresh(read)
 try{
  refresh.schedule();await vi.advanceTimersByTimeAsync(100)
  refresh.schedule();await vi.advanceTimersByTimeAsync(50)
  expect(read).toHaveBeenCalledTimes(1)
  for(let i=0;i<100;i++)refresh.schedule()
  await vi.advanceTimersByTimeAsync(500);expect(read).toHaveBeenCalledTimes(1)
  finish();await vi.advanceTimersByTimeAsync(150);expect(read).toHaveBeenCalledTimes(2)
  refresh.schedule();refresh.dispose();await vi.advanceTimersByTimeAsync(500);expect(read).toHaveBeenCalledTimes(2)
 }finally{refresh.dispose();vi.useRealTimers()}
})
