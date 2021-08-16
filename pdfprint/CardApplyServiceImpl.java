package org.hdbsp.visitor.app.service.impl;

import java.io.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

import com.itextpdf.text.*;
import com.itextpdf.text.pdf.*;
import liquibase.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.activiti.rest.service.api.engine.variable.RestVariable;
import org.activiti.rest.service.api.runtime.process.ProcessInstanceCreateRequest;
import org.activiti.rest.service.api.runtime.process.ProcessInstanceResponse;
import org.apache.commons.collections4.CollectionUtils;
import org.hdbsp.visitor.api.dto.EmployeeUserUnitDTO;
import org.hdbsp.visitor.api.dto.FileInfoDTO;
import org.hdbsp.visitor.app.service.CardApplyService;
import org.hdbsp.visitor.domain.entity.CardApply;
import org.hdbsp.visitor.domain.repository.CardApplyRepository;
import org.hdbsp.visitor.infra.constant.Constants;
import org.hdbsp.visitor.infra.feign.HfleRemoteServiceFeign;
import org.hdbsp.visitor.infra.feign.HpfmRemoteServiceFeign;
import org.hdbsp.visitor.infra.util.CommonPdfTemplateUtil;
import org.hdbsp.visitor.infra.util.DateUtils;
import org.hdbsp.visitor.infra.util.PdfUtil;
import org.hdbsp.visitor.infra.util.WorkflowUtils;
import org.hzero.boot.interfaces.sdk.dto.RequestPayloadDTO;
import org.hzero.boot.interfaces.sdk.dto.ResponsePayloadDTO;
import org.hzero.boot.interfaces.sdk.invoke.InterfaceInvokeSdk;
import org.hzero.boot.platform.code.builder.CodeRuleBuilder;
import org.hzero.boot.platform.lov.annotation.ProcessLovValue;
import org.hzero.boot.platform.profile.ProfileClient;
import org.hzero.boot.workflow.WorkflowClient;
import org.hzero.core.message.MessageAccessor;
import org.hzero.core.util.EncryptionUtils;
import org.hzero.mybatis.domian.Condition;
import org.hzero.mybatis.util.Sqls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import io.choerodon.core.domain.Page;
import io.choerodon.core.exception.CommonException;
import io.choerodon.core.oauth.CustomUserDetails;
import io.choerodon.core.oauth.DetailsHelper;
import io.choerodon.mybatis.pagehelper.domain.PageRequest;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;
import javax.smartcardio.Card;

/**
 * 门禁卡申请应用服务默认实现
 *
 * @author pengbo.wang@hand-china.com 2020-05-07 14:19:32
 */
@Service
@Slf4j
public class CardApplyServiceImpl implements CardApplyService {

    private static Logger logger = LoggerFactory.getLogger(CardApplyServiceImpl.class);

    private static String AES_KEY="kr2RTnBjVYBAP5H3789qjA==";

    @Autowired
    private CardApplyRepository cardApplyRepository;
    @Autowired
    private HpfmRemoteServiceFeign hpfmRemoteServiceFeign;
    @Autowired
    private HfleRemoteServiceFeign hfleRemoteServiceFeign;
    @Autowired
    private WorkflowClient workflowClient;
    @Autowired
    private CodeRuleBuilder codeRuleBuilder;
    @Autowired
    private ProfileClient profileClient;
    @Autowired
    private InterfaceInvokeSdk interfaceInvokeSdk;

    /**
     * 获取门禁卡申请分页列表
     *
     * @param cardApply   门禁卡申请信息
     * @param pageRequest 分页信息
     * @return Page<cardApply>
     */
    @Override
    @ProcessLovValue
    public Page<CardApply> selectList(PageRequest pageRequest, CardApply cardApply) {
        Page<CardApply> pageList = cardApplyRepository.selectList(pageRequest, cardApply);
        //添加创建人和最后更新人
        if (!pageList.getContent().isEmpty()) {
            for (CardApply cp : pageList.getContent()) {
                cp.setCreateName(cp.getCreatedBy() != null ? this.selectUserNameByUserId(cp.getTenantId(), cp.getCreatedBy()) : null);
                cp.setLastUpdateName(cp.getLastUpdatedBy() != null ? this.selectUserNameByUserId(cp.getTenantId(), cp.getLastUpdatedBy()) : null);
            }
        }
        return pageList;
    }


    /**
     * 获取门禁卡申请明细
     *
     * @param id 门禁卡申请主键Id
     * @return CardApply
     */
    @Override
    public CardApply detail(Long tenantId, Long id) {
        CardApply cardApply = cardApplyRepository.selectByPrimaryKey(id);
        if (!StringUtils.isEmpty(cardApply.getAttachmentUuid())) {
            List<FileInfoDTO> fileInfoDTOList = hfleRemoteServiceFeign.queryFile(tenantId, cardApply.getAttachmentUuid(), null);
            for (int i = 1; i <= fileInfoDTOList.size(); i++) {
                FileInfoDTO fileInfoDTO = fileInfoDTOList.get(i - 1);
                fileInfoDTO.setProductImageId((long) i);
                fileInfoDTO.setOrderSql((long) i);
                if (i == 1) {
                    fileInfoDTO.setPrimaryFlag(0L);
                } else {
                    fileInfoDTO.setPrimaryFlag(1L);
                }
            }
            cardApply.setProductImageList(fileInfoDTOList);
        }
        cardApply.setCreateName(cardApply.getCreatedBy() != null ? this.selectUserNameByUserId(cardApply.getTenantId(), cardApply.getCreatedBy()) : null);
        cardApply.setLastUpdateName(cardApply.getLastUpdatedBy() != null ? this.selectUserNameByUserId(cardApply.getTenantId(), cardApply.getLastUpdatedBy()) : null);
        return cardApply;
    }

    /**
     * 批量新增或修改
     *
     * @param cardApplyList 申请id
     * @return List<CardApply>
     */
    @Override
    @Transactional(rollbackFor = {Exception.class})
    public List<CardApply> batchInsertOrUpdate(List<CardApply> cardApplyList) {
        if (CollectionUtils.isEmpty(cardApplyList)) {
            return cardApplyList;
        } else {
            for (CardApply cardApply : cardApplyList) {
                this.insertOrUpdate(cardApply);
            }
        }
        return cardApplyList;
    }

    @Override
    public void remove(Long applyId) {
        cardApplyRepository.deleteByPrimaryKey(applyId);
    }

    @Override
    public void printCard(HttpServletResponse response, Long tenantId, List<Long> applyIdList) throws ServletException, IOException, DocumentException {
        //String fileName = "门禁卡.pdf";
        List<CardApply> cardApplyList = cardApplyRepository.selectByCondition(Condition.builder(CardApply.class).andWhere(Sqls.custom()
                .andIn(CardApply.FIELD_APPLY_ID, applyIdList)).build());
        String classUrl = this.getClass().getResource("/").getPath() + "static";
        System.out.println(classUrl);
        //确定根目录
        String systemPath = System.getProperty("user.dir");
        if (!new File(classUrl).exists()) {
            File file = new File(systemPath + "/static");
            if (!file.exists()) {
                if (!file.mkdir()) {
                    throw new CommonException("创建临时文件夹失败!");
                }
            }
            classUrl = systemPath + "/static";
        }
        String templatePath = profileClient.getProfileValueByOptions("HVIT.VISITOR_CARD");
        String fileName = UUID.randomUUID().toString() + ".pdf";
        String targetPath = classUrl + "/" + fileName;
        //组装数据
        List<Map<String, Object>> dataList = new ArrayList<>();
        for (CardApply cardApply : cardApplyList) {
            Map<String, Object> imgMap = new HashMap<>(16);
            Map<String, Object> formMap = new HashMap<>(16);
            List<FileInfoDTO> fileInfoDTOList = hfleRemoteServiceFeign.queryFile(tenantId, cardApply.getAttachmentUuid(), null);
            formMap.put(CardApply.FIELD_NAME, cardApply.getName());
            formMap.put(CardApply.FIELD_UNIT, cardApply.getUnit());
            formMap.put(CardApply.FIELD_DUTY, cardApply.getDuty());
            if(1==cardApply.getEffectiveFlag()){
                formMap.put(CardApply.FIELD_END_DATE, "长期有效");
            }else{
                formMap.put(CardApply.FIELD_END_DATE, (new SimpleDateFormat(Constants.DateConstant.DATE_FORMAT).format(cardApply.getEndDate())));
            }
            if (CollectionUtils.isNotEmpty(fileInfoDTOList)) {
                imgMap.put("headImage", fileInfoDTOList.get(0).getFileUrl());
            }
            Map<String, Object> param = new HashMap<>(16);
            param.put("formMap", formMap);
            param.put("imgMap", imgMap);
            dataList.add(param);
        }
        log.info("<==== 生成PDF准备数据:{}:{}", targetPath, dataList.size());
        CommonPdfTemplateUtil.multiplePage(templatePath, targetPath, dataList);
        log.info("<==== 生成PDF完成！{}", targetPath);
        //将文件转化成流进行输出
        BufferedInputStream bis = null;
        BufferedOutputStream bos = null;
        File pdfFile = new File(targetPath);
        try {
            //设置相应参数
            response.setHeader("Content-Length", String.valueOf(pdfFile.length()));
            response.setHeader("Content-Disposition", "attachment;filename=" + fileName);
            response.setCharacterEncoding("utf-8");
            //将文件转化成流进行输出
            bis = new BufferedInputStream(new FileInputStream(targetPath));
            bos = new BufferedOutputStream(response.getOutputStream());
            byte[] buff = new byte[2048];
            int bytesRead;
            while (-1 != (bytesRead = bis.read(buff, 0, buff.length))) {
                bos.write(buff, 0, bytesRead);
            }
        } catch (Exception e) {
            log.error("<==== HmeDistributionListQueryServiceImpl.multiplePrint.outputPDFFile Error", e);
            throw new CommonException("Exception", e.getMessage());
        } finally {
            try {
                if (bis != null) {
                    bis.close();
                }
                if (bos != null) {
                    bos.close();
                }
            } catch (IOException e) {
                log.error("<==== HmeDistributionListQueryServiceImpl.multiplePrint.closeIO Error", e);
            }
        }

        //删除临时文件
        if (!pdfFile.delete()) {
            log.info("<==== HmeDistributionListQueryServiceImpl.multiplePrint.pdfFile Failed: {}", targetPath);
        }

    }

    /**
     * 门禁卡申请创建或更新
     *
     * @param cardApply 门禁卡申请信息
     * @return CardApply
     */
    @Override
    @Transactional(rollbackFor = {Exception.class})
    public CardApply insertOrUpdate(CardApply cardApply) {
        if (cardApply == null) {
            return null;
        } else {
            if (cardApply.getApplyId() == null) {
                //生成编码
                String code = codeRuleBuilder.generateCode(cardApply.getTenantId(), Constants.CodeRules.CARD_APPLY_CODE, Constants.CodeRules.DEFAULT_RULE_CODE_LEVEL_CODE, Constants.CodeRules.DEFAULT_RULE_CODE_LEVEL_VALUE, null);
                cardApply.setApplyCode(code);
                //数据校验
                CardApply cardApply1 = new CardApply();
                cardApply1.setApplyCode(code);
                List<CardApply> select = cardApplyRepository.select(cardApply1);
                if (!CollectionUtils.isEmpty(select)) {
                    throw new CommonException(MessageAccessor.getMessage("hvit.error.applyCode.repeat").getDesc());
                }
                cardApply.setApplyStatus(Constants.ApplyStatus.NEW);
                cardApply.setApplyDate(DateUtils.getNow(Constants.DateConstant.DATE_FORMAT));
                cardApplyRepository.insertSelective(cardApply);
            } else {
                CardApply cardApplyDb = cardApplyRepository.selectByPrimaryKey(cardApply);
                Assert.notNull(cardApplyDb, "error.data_not_exists");
                Assert.isTrue(Objects.equals(cardApplyDb.getTenantId(), cardApply.getTenantId()), "error.data_invalid");
                cardApply.setApplyDate(cardApplyDb.getApplyDate());
                cardApply.setObjectVersionNumber(cardApplyDb.getObjectVersionNumber());
                cardApplyRepository.updateByPrimaryKey(cardApply);
            }
            return cardApply;
        }
    }

    @Override
    public CardApply undo(Long tenantId, Long applyId) {
        //更新状态为新建
        CardApply cardApply = cardApplyRepository.selectByPrimaryKey(applyId);
        if (!cardApply.getApplyStatus().equals(Constants.ApplyStatus.APPROVING)) {
            throw new CommonException(MessageAccessor.getMessage("hvit.status.not.approving").getDesc());
        }
        cardApply.setApplyStatus(Constants.ApplyStatus.NEW);
        cardApplyRepository.updateOptional(cardApply, CardApply.FIELD_APPLY_STATUS);
        return cardApply;
    }

    /**
     * 门禁卡申请提交
     *
     * @param tenantId
     * @param applyId
     * @return
     */
    @Override
    public CardApply submit(Long tenantId, Long applyId) {
        CardApply cardApply = cardApplyRepository.selectByPrimaryKey(applyId);
        if (!cardApply.getApplyStatus().equals(Constants.ApplyStatus.NEW) && !cardApply.getApplyStatus().equals(Constants.ApplyStatus.REJECT)) {
            throw new CommonException(MessageAccessor.getMessage("hvit.status.can.not.submit").getDesc());
        }
        //提交工作流
        CustomUserDetails customUserDetails = DetailsHelper.getUserDetails();
        List<EmployeeUserUnitDTO> emp = hpfmRemoteServiceFeign.userInfo(tenantId, customUserDetails.getUserId(), null);
        String empNum = !CollectionUtils.isEmpty(emp) ? emp.get(0).getEmployeeNum() : null;
        String businessKey = Constants.WorkFlowConstants.ACCESSCARD_APPLY.concat(":").concat(applyId.toString());
        ProcessInstanceCreateRequest request = WorkflowUtils.createWorkflowRequest(Constants.WorkFlowConstants.ACCESSCARD_APPLY, businessKey);
        List<RestVariable> restVariables = new ArrayList<>();
        restVariables.add(WorkflowUtils.setRestVariable(CardApply.FIELD_TENANT_ID, tenantId.toString()));
        restVariables.add(WorkflowUtils.setRestVariable(CardApply.FIELD_APPLY_ID, applyId.toString()));
        restVariables.add(WorkflowUtils.setRestVariable(Constants.WorkFlowConstants.FIELD_EMPLOYEE_NUM, empNum));
        restVariables.add(WorkflowUtils.setRestVariable(Constants.WorkFlowConstants.FIELD_USER_ID, customUserDetails.getUserId().toString()));
        restVariables.add(WorkflowUtils.setRestVariable(CardApply.FIELD_URGENT_FLAG, cardApply.getUrgentFlag()));
        request.setVariables(restVariables);
        ResponseEntity<ProcessInstanceResponse> processInstance = workflowClient.startUp(tenantId, empNum, request);
        ;

        //状态修改为一提交
        cardApply.setApplyStatus(Constants.ApplyStatus.APPROVING);
        cardApply.setTaskId(Objects.requireNonNull(processInstance.getBody()).getId());
        cardApplyRepository.updateOptional(cardApply, CardApply.FIELD_APPLY_STATUS, CardApply.FIELD_TASK_ID);

        return cardApply;
    }

    @Override
    @Transactional(rollbackFor = {Exception.class})
    public CardApply agree(Long tenantId, Long applyId, Long userId) {
        //更新状态为审批通过
        CardApply cardApply = cardApplyRepository.selectByPrimaryKey(applyId);
        if (!cardApply.getApplyStatus().equals(Constants.ApplyStatus.APPROVING)) {
            throw new CommonException(MessageAccessor.getMessage("hvit.status.not.approving").getDesc());
        }
        cardApply.setApplyStatus(Constants.ApplyStatus.APPROVED);
        cardApplyRepository.updateOptional(cardApply, CardApply.FIELD_APPLY_STATUS);
        return cardApply;
    }

    @Override
    @Transactional(rollbackFor = {Exception.class})
    public CardApply refuse(Long tenantId, Long applyId, Long userId) {
        //更新状态为审批拒绝
        CardApply cardApply = cardApplyRepository.selectByPrimaryKey(applyId);
        if (!cardApply.getApplyStatus().equals(Constants.ApplyStatus.APPROVING)) {
            throw new CommonException(MessageAccessor.getMessage("hvit.status.not.approving").getDesc());
        }
        cardApply.setApplyStatus(Constants.ApplyStatus.REJECT);
        cardApplyRepository.updateOptional(cardApply, CardApply.FIELD_APPLY_STATUS);
        return cardApply;
    }

    /**
     * 根据用户id获取用户真实姓名
     *
     * @param tenantId 租户id
     * @param userId   用户id
     * @return String
     */
    @Override
    public String selectUserNameByUserId(Long tenantId, Long userId) {
        List<EmployeeUserUnitDTO> userInfoList = hpfmRemoteServiceFeign.userInfo(tenantId, userId, null);
        if (!CollectionUtils.isEmpty(userInfoList)) {
            return userInfoList.get(0).getRealName();
        }
        return null;
    }

    private void printPdf(Long tenantId, List<CardApply> cardApplyList,HttpServletResponse response) {


    }
}
