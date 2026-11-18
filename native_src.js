import { Capacitor } from '@capacitor/core';
import { Filesystem, Directory } from '@capacitor/filesystem';
import { Share } from '@capacitor/share';
window.RoninNative = Capacitor.isNativePlatform() ? { Filesystem: Filesystem, Directory: Directory, Share: Share, platform: Capacitor.getPlatform() } : null;
