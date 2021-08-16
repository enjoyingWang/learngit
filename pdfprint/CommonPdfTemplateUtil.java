package org.hdbsp.visitor.infra.util;

import com.itextpdf.text.*;
import com.itextpdf.text.pdf.*;
import lombok.extern.slf4j.Slf4j;
import org.hdbsp.visitor.infra.constant.Constants;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @Classname PdfTemplateUtil
 * @Description 根据PDF模板生成打印条码
 * @Date 2019/12/25 10:49
 * @Author pengbo.wang01
 */
@Component
@Slf4j
public class CommonPdfTemplateUtil {

    private static BaseFont bf = null;
    private static Font fontChinese = null;
    {
        try {
            bf= BaseFont.createFont(Constants.PdfConstants.FRONT_NAME, Constants.PdfConstants.FRONT_ENCODING, BaseFont.NOT_EMBEDDED);
            fontChinese = new com.itextpdf.text.Font(bf, 10, com.itextpdf.text.Font.BOLD);
            //bf = BaseFont.createFont("STSongStd-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
            //com.itextpdf.text.Font fontChinese14Bold = new com.itextpdf.text.Font(bfChinese, 10, com.itextpdf.text.Font.BOLD);
        } catch (DocumentException e) {
            log.error("<==== PdfTemplateUtil init error:{}:{}",e.getMessage(),e);
            e.printStackTrace();
        } catch (IOException e) {
            log.error("<==== PdfTemplateUtil init error:{}:{}",e.getMessage(),e);
            e.printStackTrace();
        }
    }

    /**
     * @Description 生成单页数据
      * @param templatePath
     * @param dataMap
     * @return com.itextpdf.text.pdf.PdfReader
     * @Date 2019/12/25 15:21
     * @Author pengbo.wang01
     */
    public static PdfReader generateSinglePdf(String templatePath,Map<String,Object> dataMap){
        if(StringUtils.isEmpty(templatePath) || CollectionUtils.isEmpty(dataMap)){
            return null;
        }
        PdfReader reader = null;
        ByteArrayOutputStream bos = null;
        PdfStamper stamper = null;
        try {
            // 读取pdf模板
            reader = new PdfReader(templatePath);
            bos = new ByteArrayOutputStream();
            stamper = new PdfStamper(reader, bos);
            AcroFields form = stamper.getAcroFields();
            //遍历数据
            Map<String,Object> formMap = (Map<String,Object>)dataMap.get("formMap");
            // 添加所创建的字体
            form.addSubstitutionFont(bf);
            for(String key : formMap.keySet()){
                if(formMap.get(key) != null){
                    String value = formMap.get(key).toString();
                    form.setField(key,value);

                }
            }
            //图片类的内容处理
            Map<String,String> imgmap = (Map<String,String>)dataMap.get("imgMap");
            for(String key : imgmap.keySet()) {
                String value = imgmap.get(key);
                String imgpath = value;
                log.info("<==== PdfTemplateUtil.generateSinglePdf imageKey:{}", key);
                int pageNo = form.getFieldPositions(key).get(0).page;
                Rectangle signRect = form.getFieldPositions(key).get(0).position;
                float x = signRect.getLeft();
                float y = signRect.getBottom();
                //根据路径读取图片
                Image image = Image.getInstance(imgpath);
                //获取图片页面
                PdfContentByte under = stamper.getOverContent(pageNo);
                //图片大小自适应
                image.scaleToFit(signRect.getWidth(), signRect.getHeight());
                //添加图片
                image.setAbsolutePosition(x, y);
                under.addImage(image);
            }

            // 如果为false，生成的PDF文件可以编辑，如果为true，生成的PDF文件不可以编辑
            stamper.setFormFlattening(true);
            stamper.close();
            return new PdfReader(bos.toByteArray());
        } catch (IOException e) {
            log.error("<==== PdfTemplateUtil generateSinglePdf error:{}:{}",e.getMessage(),e);
            e.printStackTrace();
        } catch (DocumentException e) {
            log.error("<==== PdfTemplateUtil generateSinglePdf DocumentException:{}:{}",e.getMessage(),e);
            e.printStackTrace();
        }finally {
            if(stamper != null){
                try {
                    stamper.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if(reader != null){
                reader.close();
            }
            if(bos != null){
                try {
                    bos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    /**
     * @Description 生成多页指定PDF
      * @param templatePath
     * @param targetPdfPath
     * @param dataList
     * @return boolean
     * @Date 2019/12/25 15:21
     * @Author pengbo.wang01
     */
    public static boolean multiplePage(String templatePath,String targetPdfPath, java.util.List<Map<String,Object>> dataList){
        boolean result = false;
        if(StringUtils.isEmpty(templatePath) || CollectionUtils.isEmpty(dataList) || StringUtils.isEmpty(targetPdfPath)){
            return result;
        }
        java.util.List<PdfReader> pageList = new ArrayList();
        for (Map<String,Object> dataMap:dataList) {
            pageList.add(generateSinglePdf(templatePath,dataMap));
        }
        Document document = new Document();
        FileOutputStream out = null;
        PdfCopy copy = null;
        try {
            // 输出流
            out = new FileOutputStream(targetPdfPath);
            copy = new PdfCopy(document,out);
            document.open();
            for (int k = 0; k < pageList.size(); k++) {
                document.newPage();
                PdfImportedPage importPage = copy.getImportedPage(pageList.get(k), 1);
                PdfImportedPage importPage2 = copy.getImportedPage(pageList.get(k), 2);
                copy.addPage(importPage);
                copy.addPage(importPage2);
            }
            result = true;
        }catch (Exception e){
            log.error("<==== PdfTemplateUtil multiplePage exception:{}:{}",e.getMessage(),e);
            e.printStackTrace();
            result = false;
        }finally {
            if(copy != null){
                copy.close();
            }
            if(document != null){
                document.close();
            }
            if(out != null){
                try {
                    out.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        return result;
    }
}
