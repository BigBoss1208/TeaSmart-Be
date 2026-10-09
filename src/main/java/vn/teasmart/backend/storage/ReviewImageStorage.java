package vn.teasmart.backend.storage;

public interface ReviewImageStorage {
    String stage(byte[] bytes, String extension);
    void finalizeFile(String key);
    byte[] read(String key);
    void discard(String key);
    void deleteAfterCommit(String key);
    void retryDeletes();
}
