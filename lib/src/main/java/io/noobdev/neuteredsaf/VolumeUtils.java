package io.noobdev.neuteredsaf;

import android.os.Environment;
import android.support.annotation.NonNull;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import io.noobdev.neuteredsaf.compat.IOUtils;

public final class VolumeUtils {
    private static final String TAG = VolumeUtils.class.getSimpleName();
    private static final String VOLD_PREFIX = "/dev/block/vold/";
    private static final String MNT_MEDIA_RW_PREFIX = "/mnt/media_rw/";
    private static final String INTERNAL_STORAGE_ID = "<internal>";

    public static class Volume {
        public boolean isPrimary;
        public String id;
        public String mountPoint;
    }

    // Like getmntent()...
    private static class MountEntry {
        String fsname;
        String dir;
        String type;
        String opts;
        int freq;
        int passno;
    }

    @NonNull
    public static Volume[] getVolumes() {
        ArrayList<MountEntry> mountEntries = new ArrayList<>();
        ArrayList<Volume> volumes = new ArrayList<>();
        HashMap<String, String> sdcardfsMap = new HashMap<>();

        FileReader fr = null;
        BufferedReader br = null;
        try {
            fr = new FileReader("/proc/mounts");
            br = new BufferedReader(fr);
            String line;

            while ((line = br.readLine()) != null) {
                String[] split = line.split("[ \t]");
                if (split.length != 6) {
                    Log.e(TAG, "Invalid mount line: " + line);
                    continue;
                }

                MountEntry mountEntry = new MountEntry();
                mountEntry.fsname = split[0];
                mountEntry.dir = split[1];
                mountEntry.type = split[2];
                mountEntry.opts = split[3];
                mountEntry.freq = Integer.parseInt(split[4]);
                mountEntry.passno = Integer.parseInt(split[5]);
                mountEntries.add(mountEntry);

                if ("sdcardfs".equals(mountEntry.type)) {
                    sdcardfsMap.put(mountEntry.fsname, mountEntry.dir);
                }
            }

            Volume volume = new Volume();
            volume.isPrimary = true;
            volume.id = INTERNAL_STORAGE_ID;
            volume.mountPoint = Environment.getExternalStorageDirectory().getPath();
            volumes.add(volume);

            for (MountEntry entry : mountEntries) {
                if (entry.fsname.startsWith(VOLD_PREFIX)) {
                    volume = new Volume();
                    volume.isPrimary = false;
                    volume.id = entry.fsname.substring(VOLD_PREFIX.length());
                    if (sdcardfsMap.containsKey(entry.dir)) {
                        volume.mountPoint = sdcardfsMap.get(entry.dir);
                    } else if (entry.dir.startsWith(MNT_MEDIA_RW_PREFIX)) {
                        volume.mountPoint = "/storage/" +
                                entry.dir.substring(MNT_MEDIA_RW_PREFIX.length());
                    } else {
                        volume.mountPoint = entry.dir;
                    }
                    volumes.add(volume);
                }
            }
        } catch (FileNotFoundException e) {
            Log.e(TAG, "/proc/mounts does not exist", e);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read mounts", e);
        } finally {
            IOUtils.closeQuietly(br);
            IOUtils.closeQuietly(fr);
        }

        return volumes.toArray(new Volume[volumes.size()]);
    }
}
