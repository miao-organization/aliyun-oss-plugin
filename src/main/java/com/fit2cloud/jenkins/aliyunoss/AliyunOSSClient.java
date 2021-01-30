package com.fit2cloud.jenkins.aliyunoss;

import com.aliyun.oss.OSSClient;
import com.aliyun.oss.model.Bucket;
import com.aliyun.oss.model.ObjectMetadata;
import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import org.apache.commons.lang.time.DurationFormatUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.StringTokenizer;

public class AliyunOSSClient {
    private static final String fpSeparator = ";";

    public static boolean validateAliyunAccount(
            final String aliyunAccessKey, final String aliyunSecretKey) throws AliyunOSSException {
        try {
            OSSClient client = new OSSClient(aliyunAccessKey, aliyunSecretKey);
            client.listBuckets();
        } catch (Exception e) {
            throw new AliyunOSSException("阿里云账号验证失败：" + e.getMessage());
        }
        return true;
    }


    public static boolean validateOSSBucket(String aliyunAccessKey,
                                            String aliyunSecretKey, String bucketName) throws AliyunOSSException {
        try {
            OSSClient client = new OSSClient(aliyunAccessKey, aliyunSecretKey);
            return client.doesBucketExist(bucketName);
        } catch (Exception e) {
            throw new AliyunOSSException("验证Bucket名称失败：" + e.getMessage());
        }
    }


    public static int upload(AbstractBuild<?, ?> build, BuildListener listener,
                             String aliyunAccessKey, String aliyunSecretKey, String aliyunEndPointSuffix, String bucketName, String expFP, String expVP) throws AliyunOSSException {
        OSSClient c = new OSSClient(aliyunAccessKey, aliyunSecretKey);
        List<Bucket> buckets = c.listBuckets();
        String location = null;
        for (Bucket bucket : buckets) {
            if (bucketName.equals(bucket.getName())) {
                location = bucket.getLocation();
            }
        }
        String endpoint = "http://" + location + aliyunEndPointSuffix;
        listener.getLogger().println("endpoint:" + endpoint);
        OSSClient client = new OSSClient(endpoint, aliyunAccessKey, aliyunSecretKey);
        int filesUploaded = 0; // Counter to track no. of files that are uploaded
        try {
            FilePath workspacePath = build.getWorkspace();
            if (workspacePath == null) {
                listener.getLogger().println("工作空间中没有任何文件.");
                return filesUploaded;
            }
            StringTokenizer strTokens = new StringTokenizer(expFP, fpSeparator);
            FilePath[] paths = null;

            listener.getLogger().println("开始上传到阿里云OSS...");
            listener.getLogger().println("上传endpoint是：" + endpoint);

            while (strTokens.hasMoreElements()) {
                String fileName = strTokens.nextToken();
                String embeddedVP = null;
                if (fileName != null) {
                    int embVPSepIndex = fileName.indexOf("::");
                    if (embVPSepIndex != -1) {
                        if (fileName.length() > embVPSepIndex + 1) {
                            embeddedVP = fileName.substring(embVPSepIndex + 2, fileName.length());
                            if (Utils.isNullOrEmpty(embeddedVP)) {
                                embeddedVP = null;
                            }
                            if (embeddedVP != null && !embeddedVP.endsWith(Utils.FWD_SLASH)) {
                                embeddedVP = embeddedVP + Utils.FWD_SLASH;
                            }
                        }
                        fileName = fileName.substring(0, embVPSepIndex);
                    }
                }

                if (Utils.isNullOrEmpty(fileName)) {
                    return filesUploaded;
                }

                FilePath fp = new FilePath(workspacePath, fileName);

                if (fp.exists() && !fp.isDirectory()) {
                    paths = new FilePath[1];
                    paths[0] = fp;
                } else {
                    paths = workspacePath.list(fileName);
                }

                if (paths.length != 0) {
                    for (FilePath src : paths) {
                        String key = "";
                        if (Utils.isNullOrEmpty(expVP)
                                && Utils.isNullOrEmpty(embeddedVP)) {
                            key = src.getName();
                        } else {
                            String prefix = expVP;
                            if (!Utils.isNullOrEmpty(embeddedVP)) {
                                if (Utils.isNullOrEmpty(expVP)) {
                                    prefix = embeddedVP;
                                } else {
                                    prefix = expVP + embeddedVP;
                                }
                            }
                            key = prefix + src.getName();
                        }
                        long startTime = System.currentTimeMillis();
                        InputStream inputStream = src.read();
                        try {
                            // 创建上传Object的Metadata
                            ObjectMetadata metadata = new ObjectMetadata();
                            // 上传的文件的长度
                            metadata.setContentLength(inputStream.available());
                            // 指定该Object被下载时的内容编码格式
                            metadata.setContentEncoding("utf-8");
                            // 如果没有扩展名则填默认值application/octet-stream
                            metadata.setContentType(getContentType(fileName));
                            listener.getLogger().println("File: " + src.getName() + ", Content Type: " + metadata.getContentType());
                            client.putObject(bucketName, key, inputStream, metadata);
                        } finally {
                            try {
                                inputStream.close();
                            } catch (IOException e) {
                            }
                        }
                        long endTime = System.currentTimeMillis();
                        listener.getLogger().println("Uploaded object [" + key + "] in " + getTime(endTime - startTime));
                        listener.getLogger().println("版本下载地址:" + "http://" + bucketName + "." + location + aliyunEndPointSuffix + "/" + key);
                        filesUploaded++;
                    }
                } else {
                    listener.getLogger().println(expFP + "下未找到Artifacts，请确认\"要上传的Artifacts\"中路径配置正确或部署包已正常生成，如pom.xml里assembly插件及配置正确。");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new AliyunOSSException(e.getMessage(), e.getCause());
        }
        return filesUploaded;
    }

    public static String getTime(long timeInMills) {
        return DurationFormatUtils.formatDuration(timeInMills, "HH:mm:ss.S") + " (HH:mm:ss.S)";
    }


    /**
     * @return java.lang.String
     * @Param [fileName]
     * @description 获取文件后缀
     * @author hutiyong
     * @date 2020/5/8
     */
    public static String getContentType(String fileName) {
        // 文件的后缀名
        //text/json
        String fileExtension = fileName.substring(fileName.lastIndexOf(".")).toLowerCase();
        if (".bmp".equalsIgnoreCase(fileExtension)) {
            return "image/bmp";
        }
        if (".gif".equalsIgnoreCase(fileExtension)) {
            return "image/gif";
        }
        if (".jpeg".equalsIgnoreCase(fileExtension) || ".jpg".equalsIgnoreCase(fileExtension) || ".png".equalsIgnoreCase(fileExtension)) {
            return "image/jpg";
        }
        if (".html".equalsIgnoreCase(fileExtension)) {
            return "text/html";
        }
        if (".txt".equalsIgnoreCase(fileExtension)) {
            return "text/plain";
        }
        if (".vsd".equalsIgnoreCase(fileExtension)) {
            return "application/vnd.visio";
        }
        if (".ppt".equalsIgnoreCase(fileExtension) || "pptx".equalsIgnoreCase(fileExtension)) {
            return "application/vnd.ms-powerpoint";
        }
        if (".doc".equalsIgnoreCase(fileExtension) || "docx".equalsIgnoreCase(fileExtension)) {
            return "application/msword";
        }
        if (".xml".equalsIgnoreCase(fileExtension)) {
            return "text/xml";
        }
        if (".mp3".equalsIgnoreCase(fileExtension)) {
            return "audio/mpeg";
        }
        if (".mp4".equalsIgnoreCase(fileExtension)) {
            return "video/mp4";
        }
        if (".json".equalsIgnoreCase(fileExtension)) {
            return "application/json";
        }
        if (".pdf".equalsIgnoreCase(fileExtension)) {
            return "application/pdf";
        }
        // 默认返回类型
        return "application/octet-stream";
    }


}
