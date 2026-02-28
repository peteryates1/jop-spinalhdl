package com.jopdesign.fat32;
import java.io.IOException;
public class VerifyImage {
    public static void main(String[] args) throws Exception {
        FileBlockDevice dev = new FileBlockDevice(args[0]);
        dev.init();
        Fat32FileSystem fs = new Fat32FileSystem(dev);
        fs.mount(0);
        // Create files and leave them
        DirEntry e = fs.createFile(fs.getRootCluster(), "TEST.TXT");
        Fat32OutputStream out = fs.openFileForWrite(e);
        String msg = "Hello from JOP FAT32!\r\n";
        for (int i = 0; i < msg.length(); i++) out.write(msg.charAt(i));
        out.close();

        e = fs.createFile(fs.getRootCluster(), "Hello from JOP.txt");
        out = fs.openFileForWrite(e);
        msg = "LFN test content.\r\n";
        for (int i = 0; i < msg.length(); i++) out.write(msg.charAt(i));
        out.close();

        fs.flush();
        dev.close();
        System.out.println("Files written. Mount to verify.");
    }
}
