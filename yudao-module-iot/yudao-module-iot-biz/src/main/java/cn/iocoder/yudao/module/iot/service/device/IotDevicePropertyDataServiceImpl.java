package cn.iocoder.yudao.module.iot.service.device;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.iot.api.device.dto.IotDevicePropertyReportReqDTO;
import cn.iocoder.yudao.module.iot.controller.admin.device.vo.deviceData.IotDeviceDataPageReqVO;
import cn.iocoder.yudao.module.iot.controller.admin.device.vo.deviceData.IotDeviceDataSimulatorSaveReqVO;
import cn.iocoder.yudao.module.iot.controller.admin.thingmodel.model.dataType.ThingModelDateOrTextDataSpecs;
import cn.iocoder.yudao.module.iot.dal.dataobject.device.IotDeviceDO;
import cn.iocoder.yudao.module.iot.dal.dataobject.device.IotDeviceDataDO;
import cn.iocoder.yudao.module.iot.dal.dataobject.product.IotProductDO;
import cn.iocoder.yudao.module.iot.dal.dataobject.tdengine.SelectVisualDO;
import cn.iocoder.yudao.module.iot.dal.dataobject.tdengine.ThingModelMessage;
import cn.iocoder.yudao.module.iot.dal.dataobject.thingmodel.IotThingModelDO;
import cn.iocoder.yudao.module.iot.dal.redis.deviceData.DeviceDataRedisDAO;
import cn.iocoder.yudao.module.iot.dal.tdengine.IotDevicePropertyDataMapper;
import cn.iocoder.yudao.module.iot.dal.tdengine.TdEngineDMLMapper;
import cn.iocoder.yudao.module.iot.enums.IotConstants;
import cn.iocoder.yudao.module.iot.enums.thingmodel.IotDataSpecsDataTypeEnum;
import cn.iocoder.yudao.module.iot.enums.thingmodel.IotThingModelTypeEnum;
import cn.iocoder.yudao.module.iot.framework.tdengine.core.TDengineTableField;
import cn.iocoder.yudao.module.iot.mq.producer.simulatesend.SimulateSendProducer;
import cn.iocoder.yudao.module.iot.service.product.IotProductService;
import cn.iocoder.yudao.module.iot.service.tdengine.IotThingModelMessageService;
import cn.iocoder.yudao.module.iot.service.thingmodel.IotThingModelService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.filterList;
import static cn.iocoder.yudao.module.iot.enums.ErrorCodeConstants.DEVICE_DATA_CONTENT_JSON_PARSE_ERROR;

/**
 * IoT 设备【属性】数据 Service 实现类
 *
 * @author 芋道源码
 */
@Service
@Slf4j
public class IotDevicePropertyDataServiceImpl implements IotDevicePropertyDataService {

    /**
     * 物模型的数据类型，与 TDengine 数据类型的映射关系
     */
    private static final Map<String, String> TYPE_MAPPING = MapUtil.<String, String>builder()
            .put(IotDataSpecsDataTypeEnum.INT.getDataType(), TDengineTableField.TYPE_INT)
            .put(IotDataSpecsDataTypeEnum.FLOAT.getDataType(), TDengineTableField.TYPE_FLOAT)
            .put(IotDataSpecsDataTypeEnum.DOUBLE.getDataType(), TDengineTableField.TYPE_DOUBLE)
            .put(IotDataSpecsDataTypeEnum.ENUM.getDataType(), TDengineTableField.TYPE_TINYINT) // TODO 芋艿：为什么要映射为 TINYINT 的说明？
            .put(IotDataSpecsDataTypeEnum.BOOL.getDataType(), TDengineTableField.TYPE_TINYINT) // TODO 芋艿：为什么要映射为 TINYINT 的说明？
            .put(IotDataSpecsDataTypeEnum.TEXT.getDataType(), TDengineTableField.TYPE_NCHAR)
            .put(IotDataSpecsDataTypeEnum.DATE.getDataType(), TDengineTableField.TYPE_TIMESTAMP)
            .put(IotDataSpecsDataTypeEnum.STRUCT.getDataType(), TDengineTableField.TYPE_NCHAR) // TODO 芋艿：怎么映射！！！！
            .put(IotDataSpecsDataTypeEnum.ARRAY.getDataType(), TDengineTableField.TYPE_NCHAR) // TODO 芋艿：怎么映射！！！！
            .build();

    @Value("${spring.datasource.dynamic.datasource.tdengine.url}")
    private String url;

    @Resource
    private IotDeviceService deviceService;
    @Resource
    private IotThingModelMessageService thingModelMessageService;
    @Resource
    private IotThingModelService thingModelService;
    @Resource
    private IotProductService productService;

    @Resource
    private SimulateSendProducer simulateSendProducer;

    @Resource
    private TdEngineDMLMapper tdEngineDMLMapper;

    @Resource
    private DeviceDataRedisDAO deviceDataRedisDAO;

    @Resource
    private IotDevicePropertyDataMapper devicePropertyDataMapper;


    @Override
    public void defineDevicePropertyData(Long productId) {
        // 1.1 查询产品和物模型
        IotProductDO product = productService.validateProductExists(productId);
        List<IotThingModelDO> thingModels = filterList(thingModelService.getThingModelListByProductId(productId),
                thingModel -> IotThingModelTypeEnum.PROPERTY.getType().equals(thingModel.getType()));
        // 1.2 解析 DB 里的字段
        List<TDengineTableField> oldFields = new ArrayList<>();
        try {
            oldFields.addAll(devicePropertyDataMapper.getProductPropertySTableFieldList(product.getProductKey()));
        } catch (Exception e) {
            if (!e.getMessage().contains("Table does not exist")) {
                throw e;
            }
        }

        // 2.1 情况一：如果是新增的时候，需要创建表
        List<TDengineTableField> newFields = buildTableFieldList(thingModels);
        if (CollUtil.isEmpty(oldFields)) {
            if (CollUtil.isEmpty(newFields)) {
                log.info("[defineDevicePropertyData][productId({}) 没有需要定义的属性]", productId);
                return;
            }
            newFields.add(0, new TDengineTableField(TDengineTableField.FIELD_TS, TDengineTableField.TYPE_TIMESTAMP));
            devicePropertyDataMapper.createProductPropertySTable(product.getProductKey(), newFields);
            return;
        }
        // 2.2 情况二：如果是修改的时候，需要更新表
        devicePropertyDataMapper.alterProductPropertySTable(product.getProductKey(), oldFields, newFields);
    }

    private List<TDengineTableField> buildTableFieldList(List<IotThingModelDO> thingModels) {
        return convertList(thingModels, thingModel -> {
            TDengineTableField field = new TDengineTableField(
                    StrUtil.toUnderlineCase(thingModel.getIdentifier()), // TDengine 字段默认都是小写
                    TYPE_MAPPING.get(thingModel.getProperty().getDataType()));
            if (thingModel.getProperty().getDataType().equals(IotDataSpecsDataTypeEnum.TEXT.getDataType())) {
                field.setLength(((ThingModelDateOrTextDataSpecs) thingModel.getProperty().getDataSpecs()).getLength());
            }
            return field;
        });
    }

    @Override
    public void saveDeviceData(IotDevicePropertyReportReqDTO createDTO) {
        // TODO 芋艿：这块需要实现
        // 1. 根据产品 key 和设备名称，获得设备信息
        IotDeviceDO device = deviceService.getDeviceByProductKeyAndDeviceName(createDTO.getProductKey(), createDTO.getDeviceName());
        // 2. 解析消息，保存数据
        JSONObject jsonObject = new JSONObject(createDTO.getParams());
        log.info("[saveDeviceData][productKey({}) deviceName({}) data({})]", createDTO.getProductKey(), createDTO.getDeviceName(), jsonObject);
        ThingModelMessage thingModelMessage = ThingModelMessage.builder()
                .id(jsonObject.getStr("id"))
                .sys(jsonObject.get("sys"))
                .method(jsonObject.getStr("method"))
                .params(jsonObject.get("params"))
                .time(jsonObject.getLong("time") == null ? System.currentTimeMillis() : jsonObject.getLong("time"))
                .productKey(createDTO.getProductKey())
                .deviceName(createDTO.getDeviceName())
                .deviceKey(device.getDeviceKey())
                .build();
        thingModelMessageService.saveThingModelMessage(device, thingModelMessage);
    }

    //TODO:后续捋一捋这块逻辑，先借鉴一下目前的代码
    @Override
    public void saveDeviceDataTest(ThingModelMessage thingModelMessage) {
        // 1. 根据产品 key 和设备名称，获得设备信息
        IotDeviceDO device = deviceService.getDeviceByProductKeyAndDeviceName(thingModelMessage.getProductKey(), thingModelMessage.getDeviceName());
        // 2. 保存数据
        thingModelMessageService.saveThingModelMessage(device, thingModelMessage);
    }

    //TODO:  copy 了 saveDeviceData 的逻辑，后续看看这块怎么优化
    @Override
    public void simulatorSend(IotDeviceDataSimulatorSaveReqVO simulatorReqVO) {
        // 1. 根据设备 key ，获得设备信息
        IotDeviceDO device = deviceService.getDeviceByDeviceKey(simulatorReqVO.getDeviceKey());

        // 2. 解析 content 为 JSON 对象
        JSONObject contentJson;
        try {
            contentJson = JSONUtil.parseObj(simulatorReqVO.getContent());
        } catch (Exception e) {
            throw exception(DEVICE_DATA_CONTENT_JSON_PARSE_ERROR);
        }

        // 3. 构建物模型消息
        ThingModelMessage thingModelMessage = ThingModelMessage.builder()
                .id(IdUtil.fastSimpleUUID()) // TODO:后续优化
                .sys(null)// TODO:这块先写死，后续优化
                .method("thing.event.property.post") // TODO:这块先写死，后续优化
                .params(contentJson) // 将 content 作为 params
                .time(simulatorReqVO.getReportTime()) // 使用上报时间
                .productKey(simulatorReqVO.getProductKey())
                .deviceName(device.getDeviceName())
                .deviceKey(device.getDeviceKey())
                .build();

        // 4. 发送模拟消息
        simulateSendProducer.sendSimulateMessage(thingModelMessage);
    }

    @Override
    public List<IotDeviceDataDO> getLatestDeviceProperties(@Valid IotDeviceDataPageReqVO deviceDataReqVO) {
        List<IotDeviceDataDO> list = new ArrayList<>();
        // 1. 获取设备信息
        IotDeviceDO device = deviceService.getDevice(deviceDataReqVO.getDeviceId());
        // 2. 获取设备属性最新数据
        List<IotThingModelDO> thingModelList = thingModelService.getProductThingModelListByProductKey(device.getProductKey());
        thingModelList = filterList(thingModelList, thingModel -> IotThingModelTypeEnum.PROPERTY.getType()
                .equals(thingModel.getType()));

        // 3. 过滤标识符和属性名称
        if (deviceDataReqVO.getIdentifier() != null) {
            thingModelList = filterList(thingModelList, thingModel -> thingModel.getIdentifier()
                    .toLowerCase().contains(deviceDataReqVO.getIdentifier().toLowerCase()));
        }
        if (deviceDataReqVO.getName() != null) {
            thingModelList = filterList(thingModelList, thingModel -> thingModel.getName()
                    .toLowerCase().contains(deviceDataReqVO.getName().toLowerCase()));
        }
        // 4. 获取设备属性最新数据
        thingModelList.forEach(thingModel -> {
            IotDeviceDataDO deviceData = deviceDataRedisDAO.get(device.getProductKey(), device.getDeviceName(), thingModel.getIdentifier());
            if (deviceData == null) {
                deviceData = new IotDeviceDataDO();
                deviceData.setProductKey(device.getProductKey());
                deviceData.setDeviceName(device.getDeviceName());
                deviceData.setIdentifier(thingModel.getIdentifier());
                deviceData.setDeviceId(deviceDataReqVO.getDeviceId());
                deviceData.setThingModelId(thingModel.getId());
                deviceData.setName(thingModel.getName());
                deviceData.setDataType(thingModel.getProperty().getDataType());
            }
            list.add(deviceData);
        });
        return list;
    }

    @Override
    public PageResult<Map<String, Object>> getHistoryDeviceProperties(IotDeviceDataPageReqVO deviceDataReqVO) {
        PageResult<Map<String, Object>> pageResult = new PageResult<>();
        // 1. 获取设备信息
        IotDeviceDO device = deviceService.getDevice(deviceDataReqVO.getDeviceId());
        // 2. 获取设备属性历史数据
        SelectVisualDO selectVisualDO = new SelectVisualDO();
        selectVisualDO.setDataBaseName(getDatabaseName());
        selectVisualDO.setTableName(getDeviceTableName(device.getProductKey(), device.getDeviceName()));
        selectVisualDO.setFieldName(deviceDataReqVO.getIdentifier());
        selectVisualDO.setStartTime(DateUtil.date(deviceDataReqVO.getTimes()[0].atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()).getTime());
        selectVisualDO.setEndTime(DateUtil.date(deviceDataReqVO.getTimes()[1].atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()).getTime());
        Map<String, Object> params = new HashMap<>();
        params.put("rows", deviceDataReqVO.getPageSize());
        params.put("page", (deviceDataReqVO.getPageNo() - 1) * deviceDataReqVO.getPageSize());
        selectVisualDO.setParams(params);
        pageResult.setList(tdEngineDMLMapper.selectHistoryDataList(selectVisualDO));
        pageResult.setTotal(tdEngineDMLMapper.selectHistoryCount(selectVisualDO));
        return pageResult;
    }

    private String getDatabaseName() {
        return StrUtil.subAfter(url, "/", true);
    }

    private static String getDeviceTableName(String productKey, String deviceName) {
        return String.format(IotConstants.DEVICE_TABLE_NAME_FORMAT, productKey, deviceName);
    }

}