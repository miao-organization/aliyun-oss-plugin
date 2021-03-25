package com.fit2cloud.jenkins.aliyunoss;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintStream;

public class AliyunOSSPublisher extends Publisher {

    private PrintStream logger;
    String bucketName;
    String filesPath;
    String objectPrefix;
    String expiresTime;

    public String getExpiresTime() {
        return expiresTime;
    }

    public void setExpiresTime(String expiresTime) {
        this.expiresTime = expiresTime;
    }

    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }

    public String getFilesPath() {
        return filesPath;
    }

    public void setFilesPath(String filesPath) {
        this.filesPath = filesPath;
    }

    public String getObjectPrefix() {
        return objectPrefix;
    }

    public void setObjectPrefix(String objectPrefix) {
        this.objectPrefix = objectPrefix;
    }

    @DataBoundConstructor
    public AliyunOSSPublisher(final String bucketName, final String filesPath, final String objectPrefix, final String expiresTime) {
        this.bucketName = bucketName;
        this.filesPath = filesPath;
        this.objectPrefix = objectPrefix;
        this.expiresTime = expiresTime;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.STEP;
    }

    @Override
    public DescriptorImpl getDescriptor() {

        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends
            BuildStepDescriptor<Publisher> {

        private String aliyunAccessKey;
        private String aliyunSecretKey;
        private String aliyunEndPointSuffix;

        public DescriptorImpl() {
            super(AliyunOSSPublisher.class);
            load();
        }

        @Override
        public Publisher newInstance(StaplerRequest req, JSONObject formData)
                throws FormException {
            return super.newInstance(req, formData);
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        public String getDisplayName() {
            return "上传Artifacts到阿里云OSS";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData)
                throws FormException {
            req.bindParameters(this);
            this.aliyunAccessKey = formData.getString("aliyunAccessKey");
            this.aliyunSecretKey = formData.getString("aliyunSecretKey");
            this.aliyunEndPointSuffix = formData.getString("aliyunEndPointSuffix");
            save();
            return super.configure(req, formData);
        }

        public FormValidation doCheckAccount(
                @QueryParameter String aliyunAccessKey,
                @QueryParameter String aliyunSecretKey) {
            if (Utils.isNullOrEmpty(aliyunAccessKey)) {
                return FormValidation.error("阿里云AccessKey不能为空！");
            }
            if (Utils.isNullOrEmpty(aliyunSecretKey)) {
                return FormValidation.error("阿里云SecretKey不能为空！");
            }
            try {
                AliyunOSSClient.validateAliyunAccount(aliyunAccessKey,
                        aliyunSecretKey);
            } catch (Exception e) {
                return FormValidation.error(e.getMessage());
            }
            return FormValidation.ok("验证阿里云帐号成功！");
        }

        public FormValidation doCheckBucket(@QueryParameter String val)
                throws IOException, ServletException {
            if (Utils.isNullOrEmpty(val)) {
                return FormValidation.error("Bucket不能为空！");
            }
            try {
                AliyunOSSClient.validateOSSBucket(aliyunAccessKey, aliyunSecretKey, val);
            } catch (Exception e) {
                return FormValidation.error(e.getMessage());
            }
            return FormValidation.ok();
        }

        public ListBoxModel doFillAliyunEndPointSuffixItems(@QueryParameter("aliyunEndPointSuffix") String aliyunEndPointSuffix) {
            ListBoxModel items = new ListBoxModel();
            items.add("外网地址(.aliyuncs.com)", ".aliyuncs.com");
            items.add("内网地址(-internal.aliyuncs.com)", "-internal.aliyuncs.com");
            return items;
        }

        public ListBoxModel doFillExpiresTimeItems(@QueryParameter("expiresTime") String expiresTime) {
            ListBoxModel items = new ListBoxModel();
            items.add("不显示", "no");
            items.add("显示，默认一天", "day");
            items.add("显示，默认一周", "week");
            items.add("显示，默认一月", "month");
            items.add("显示，默认一年", "year");
            items.add("显示，默认永久", "permanent");
            return items;
        }

        public FormValidation doCheckPath(@QueryParameter String val) {
            if (Utils.isNullOrEmpty(val)) {
                return FormValidation.error("Artifact路径不能为空！");
            }
            return FormValidation.ok();
        }

        public String getAliyunAccessKey() {
            return aliyunAccessKey;
        }

        public void setAliyunAccessKey(String aliyunAccessKey) {
            this.aliyunAccessKey = aliyunAccessKey;
        }

        public String getAliyunSecretKey() {
            return aliyunSecretKey;
        }

        public void setAliyunSecretKey(String aliyunSecretKey) {
            this.aliyunSecretKey = aliyunSecretKey;
        }

        public String getAliyunEndPointSuffix() {
            return aliyunEndPointSuffix;
        }

        public void setAliyunEndPointSuffix(String aliyunEndPointSuffix) {
            this.aliyunEndPointSuffix = aliyunEndPointSuffix;
        }
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {
        this.logger = listener.getLogger();
        final boolean buildFailed = build.getResult() == Result.FAILURE;
        if (buildFailed) {
            logger.println("Job构建失败,无需上传Aritfacts到阿里云OSS.");
            return true;
        }

        // Resolve file path
        String expFP = Utils.replaceTokens(build, listener, filesPath);

        if (expFP != null) {
            expFP = expFP.trim();
        }

        // Resolve virtual path
        String expVP = Utils.replaceTokens(build, listener, objectPrefix);
        if (Utils.isNullOrEmpty(expVP)) {
            expVP = null;
        }
        if (!Utils.isNullOrEmpty(expVP) && !expVP.endsWith(Utils.FWD_SLASH)) {
            expVP = expVP.trim() + Utils.FWD_SLASH;
        }

        boolean flag = false;
        try {
            int filesUploaded = AliyunOSSClient.upload(build, listener,
                    this.getDescriptor().aliyunAccessKey,
                    this.getDescriptor().aliyunSecretKey,
                    this.getDescriptor().aliyunEndPointSuffix,
                    bucketName, expFP, expVP, expiresTime);
            if (filesUploaded > 0) {
                listener.getLogger().println("上传Artifacts到阿里云OSS成功，上传文件个数:" + filesUploaded);
                flag = true;
            }

        } catch (Exception e) {
            this.logger.println("上传Artifact到阿里云OSS失败，错误消息如下:");
            this.logger.println(e.getMessage());
            e.printStackTrace(this.logger);
            flag = false;
        }
        return flag;
    }

}
