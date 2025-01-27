package cn.iocoder.yudao.module.iot.service.thingmodel;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.iot.controller.admin.thingmodel.vo.IotThingModelListReqVO;
import cn.iocoder.yudao.module.iot.controller.admin.thingmodel.vo.IotThingModelPageReqVO;
import cn.iocoder.yudao.module.iot.controller.admin.thingmodel.vo.IotThingModelSaveReqVO;
import cn.iocoder.yudao.module.iot.dal.dataobject.thingmodel.IotThingModelDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * IoT 产品物模型 Service 接口
 *
 * @author 芋道源码
 */
public interface IotThingModelService {

    /**
     * 创建产品物模型
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createThingModel(@Valid IotThingModelSaveReqVO createReqVO);

    /**
     * 更新产品物模型
     *
     * @param updateReqVO 更新信息
     */
    void updateThingModel(@Valid IotThingModelSaveReqVO updateReqVO);

    /**
     * 删除产品物模型
     *
     * @param id 编号
     */
    void deleteThingModel(Long id);

    /**
     * 获得产品物模型
     *
     * @param id 编号
     * @return 产品物模型
     */
    IotThingModelDO getThingModel(Long id);

    // TODO @芋艿：增加缓存
    /**
     * 获得产品物模型列表
     *
     * @param productId 产品编号
     * @return 产品物模型列表
     */
    List<IotThingModelDO> getThingModelListByProductId(Long productId);

    /**
     * 获得产品物模型分页
     *
     * @param pageReqVO 分页查询
     * @return 产品物模型分页
     */
    PageResult<IotThingModelDO> getProductThingModelPage(IotThingModelPageReqVO pageReqVO);

    /**
     * 获得产品物模型列表
     *
     * @param productKey 产品 Key
     * @return 产品物模型列表
     */
    List<IotThingModelDO> getProductThingModelListByProductKey(String productKey);

    /**
     * 获得产品物模型列表
     *
     * @param reqVO 列表查询
     * @return 产品物模型列表
     */
    List<IotThingModelDO> getThingModelList(IotThingModelListReqVO reqVO);
}