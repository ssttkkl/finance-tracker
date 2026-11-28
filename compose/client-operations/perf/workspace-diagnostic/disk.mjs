import { readdirSync, lstatSync } from 'node:fs';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';

export function usage(root) {
    const result = { apparent:0, allocated:0, main:0, journal:0, scratch:0, scratchDirectories:0, files:0 };
    function inspect(path, scratch=false) {
        for (const entry of readdirSync(path, { withFileTypes:true, throwIfNoEntry:false }) ?? []) {
            const ownScratch = scratch || entry.name.startsWith('.cash-review-');
            const name = join(path,entry.name);
            if (entry.isDirectory()) {
                if (ownScratch) result.scratchDirectories++;
                inspect(name,ownScratch);
            } else if (entry.isFile()) {
                let measured;
                try { measured=lstatSync(name); }
                catch (error) { if (error.code==='ENOENT') continue; throw error; }
                result.apparent+=measured.size;
                result.allocated+=measured.blocks*512;
                if (ownScratch) result.scratch+=measured.size;
                if (entry.name.endsWith('-journal') || entry.name.endsWith('-wal') || entry.name.endsWith('-shm'))
                    result.journal+=measured.size;
                else if (!ownScratch && entry.name==='client.sqlite') result.main+=measured.size;
                result.files++;
            }
        }
    }
    try { inspect(root); }
    catch (error) { if (error.code!=='ENOENT') throw error; }
    return result;
}

export function trackDisk(root) {
    const initial=usage(root);
    const peak={...initial};
    let samples=1;
    function sample() {
        const value=usage(root);
        for (const field of Object.keys(peak)) peak[field]=Math.max(value[field],peak[field]);
        samples++;
    }
    const timer=setInterval(sample,8);
    timer.unref();
    return () => {
        clearInterval(timer);
        sample();
        let reusableBytes;
        const dbPath=join(root,'client.sqlite');
        if (lstatSync(dbPath,{throwIfNoEntry:false})) {
            const db = new DatabaseSync(dbPath,{readOnly:true});
            try { reusableBytes=db.prepare('PRAGMA page_size').get().page_size*db.prepare('PRAGMA freelist_count').get().freelist_count; }
            finally { db.close(); }
        }
        return {initial,peak,after:usage(root),reusableBytes,samples,sampleIntervalMs:8};
    };
}
