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
 * ???????????????????????????????????????
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
     * ?????????????????????????????????
     *
     * @param cardApply   ?????????????????????
     * @param pageRequest ????????????
     * @return Page<cardApply>
     */
    @Override
    @ProcessLovValue
    public Page<CardApply> selectList(PageRequest pageRequest, CardApply cardApply) {
        Page<CardApply> pageList = cardApplyRepository.selectList(pageRequest, cardApply);
        //?????????????????????????????????
        if (!pageList.getContent().isEmpty()) {
            for (CardApply cp : pageList.getContent()) {
                cp.setCreateName(cp.getCreatedBy() != null ? this.selectUserNameByUserId(cp.getTenantId(), cp.getCreatedBy()) : null);
                cp.setLastUpdateName(cp.getLastUpdatedBy() != null ? this.selectUserNameByUserId(cp.getTenantId(), cp.getLastUpdatedBy()) : null);
            }
        }
        return pageList;
    }


    /**
     * ???????????????????????????
     *
     * @param id ?????????????????????Id
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
     * ?????????????????????
     *
     * @param cardApplyList ??????id
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
        //String fileName = "?????????.pdf";
        List<CardApply> cardApplyList = cardApplyRepository.selectByCondition(Condition.builder(CardApply.class).andWhere(Sqls.custom()
                .andIn(CardApply.FIELD_APPLY_ID, applyIdList)).build());
        String classUrl = this.getClass().getResource("/").getPath() + "static";
        System.out.println(classUrl);
        //???????????????
        String systemPath = System.getProperty("user.dir");
        if (!new File(classUrl).exists()) {
            File file = new File(systemPath + "/static");
            if (!file.exists()) {
                if (!file.mkdir()) {
                    throw new CommonException("???????????????????????????!");
                }
            }
            classUrl = systemPath + "/static";
        }
        String templatePath = profileClient.getProfileValueByOptions("HVIT.VISITOR_CARD");
        String fileName = UUID.randomUUID().toString() + ".pdf";
        String targetPath = classUrl + "/" + fileName;
        //????????????
        List<Map<String, Object>> dataList = new ArrayList<>();
        for (CardApply cardApply : cardApplyList) {
            Map<String, Object> imgMap = new HashMap<>(16);
            Map<String, Object> formMap = new HashMap<>(16);
            List<FileInfoDTO> fileInfoDTOList = hfleRemoteServiceFeign.queryFile(tenantId, cardApply.getAttachmentUuid(), null);
            formMap.put(CardApply.FIELD_NAME, cardApply.getName());
            formMap.put(CardApply.FIELD_UNIT, cardApply.getUnit());
            formMap.put(CardApply.FIELD_DUTY, cardApply.getDuty());
            if(1==cardApply.getEffectiveFlag()){
                formMap.put(CardApply.FIELD_END_DATE, "????????????");
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
        log.info("<==== ??????PDF????????????:{}:{}", targetPath, dataList.size());
        CommonPdfTemplateUtil.multiplePage(templatePath, targetPath, dataList);
        log.info("<==== ??????PDF?????????{}", targetPath);
        //?????????????????????????????????
        BufferedInputStream bis = null;
        BufferedOutputStream bos = null;
        File pdfFile = new File(targetPath);
        try {
            //??????????????????
            response.setHeader("Content-Length", String.valueOf(pdfFile.length()));
            response.setHeader("Content-Disposition", "attachment;filename=" + fileName);
            response.setCharacterEncoding("utf-8");
            //?????????????????????????????????
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

        //??????????????????
        if (!pdfFile.delete()) {
            log.info("<==== HmeDistributionListQueryServiceImpl.multiplePrint.pdfFile Failed: {}", targetPath);
        }

    }

    /**
     * ??????????????????????????????
     *
     * @param cardApply ?????????????????????
     * @return CardApply
     */
    @Override
    @Transactional(rollbackFor = {Exception.class})
    public CardApply insertOrUpdate(CardApply cardApply) {
        if (cardApply == null) {
            return null;
        } else {
            if (cardApply.getApplyId() == null) {
                //????????????
                String code = codeRuleBuilder.generateCode(cardApply.getTenantId(), Constants.CodeRules.CARD_APPLY_CODE, Constants.CodeRules.DEFAULT_RULE_CODE_LEVEL_CODE, Constants.CodeRules.DEFAULT_RULE_CODE_LEVEL_VALUE, null);
                cardApply.setApplyCode(code);
                //????????????
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
        //?????????????????????
        CardApply cardApply = cardApplyRepository.selectByPrimaryKey(applyId);
        if (!cardApply.getApplyStatus().equals(Constants.ApplyStatus.APPROVING)) {
            throw new CommonException(MessageAccessor.getMessage("hvit.status.not.approving").getDesc());
        }
        cardApply.setApplyStatus(Constants.ApplyStatus.NEW);
        cardApplyRepository.updateOptional(cardApply, CardApply.FIELD_APPLY_STATUS);
        return cardApply;
    }

    /**
     * ?????????????????????
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
        //???????????????
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

        //????????????????????????
        cardApply.setApplyStatus(Constants.ApplyStatus.APPROVING);
        cardApply.setTaskId(Objects.requireNonNull(processInstance.getBody()).getId());
        cardApplyRepository.updateOptional(cardApply, CardApply.FIELD_APPLY_STATUS, CardApply.FIELD_TASK_ID);

        return cardApply;
    }

    @Override
    @Transactional(rollbackFor = {Exception.class})
    public CardApply agree(Long tenantId, Long applyId, Long userId) {
        //???????????????????????????
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
        //???????????????????????????
        CardApply cardApply = cardApplyRepository.selectByPrimaryKey(applyId);
        if (!cardApply.getApplyStatus().equals(Constants.ApplyStatus.APPROVING)) {
            throw new CommonException(MessageAccessor.getMessage("hvit.status.not.approving").getDesc());
        }
        cardApply.setApplyStatus(Constants.ApplyStatus.REJECT);
        cardApplyRepository.updateOptional(cardApply, CardApply.FIELD_APPLY_STATUS);
        return cardApply;
    }

    /**
     * ????????????id????????????????????????
     *
     * @param tenantId ??????id
     * @param userId   ??????id
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
