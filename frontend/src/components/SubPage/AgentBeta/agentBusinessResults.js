// Only reviewed business fields are rendered. Service payloads and internal evidence never
// fall back to JSON.stringify in a user-facing answer.
export const BUSINESS_LABELS = {
  outstandingQuantity: '未实物归还数量', businessDirection: '业务方向',
  periodStart: '应对账期间起', periodEnd: '应对账期间止', scheduledDate: '计划对账日',
  reconciliationType: '对账类型', reconciliationPeriodMonths: '对账周期（月）',
  settledRentAmount: '已结算材料租金', plannedDate: '计划对账日', cycleMonths: '周期（月）', anchorDate: '起算日期', notYetDue: '尚未到对账日',
  settledRent: '已结算材料租金', materialRentFee: '材料租金', currency: '币种',
  pendingPeriods: '待对账期间',
  periodStatus: '对账状态', scheduledEndDate: '应对账截止日',

  projectName: '项目名称', constructionProject: '施工项目', projectId: '项目编号', managerName: '我方负责人',
  partnerName: '对方负责人', tenantUnit: '承租单位', customerName: '客户', supplierName: '供应商',
  projectBusinessType: '业务类型', projectStatusFlag: '项目状态', address: '地址', city: '城市',
  materialName: '材料名称', materialSpecification: '规格', specification: '规格', categoryName: '分类',
  materialCategory: '材料分类', countingUnit: '计数单位', pricingUnit: '计价单位', unit: '单位',
  materialId: '材料编号', mid: '材料编号', availableQty: '可供数量', inventoryQty: '库存数量',
  inventoryQuantity: '库存数量', quantity: '数量', materialNumber: '材料数量', countingQuantity: '计数数量',
  inRentQuantity: '在租数量', rentOutQuantity: '租出数量', returnQuantity: '归还数量', rentInQuantity: '租入数量',
  rentInReturnQuantity: '退租数量', marketPrice: '报价', marketPriceUnit: '报价单位', marketUpdateTime: '报价更新时间',
  stationName: '租赁站', contactPerson: '联系人', contactPhone: '联系电话', phone: '电话',
  documentName: '单据名称', documentId: '单据编号', rentDocumentName: '租出单名称', returnDocumentName: '归还单名称',
  compensationDocumentName: '赔偿单名称', rentInDocumentName: '租入单名称', rentInReturnDocumentName: '退租单名称',
  rentDocumentId: '租出单编号', returnDocumentId: '归还单编号', compensationDocumentId: '赔偿单编号',
  rentInDocumentId: '租入单编号', rentInReturnDocumentId: '退租单编号', reviewStatus: '复核状态',
  rentDate: '租出日期', returnDate: '归还日期', rentInDate: '租入日期', rentInReturnDate: '退租日期',
  compensationDate: '赔偿日期', businessDate: '业务日期', documentDate: '单据日期', createDate: '创建日期',
  date: '日期', startDate: '开始日期', endDate: '结束日期', paymentDate: '付款日期', checkDate: '盘点日期',
  createdAt: '创建时间', updatedAt: '更新时间', personInCharge: '负责人', auditor: '复核人', creator: '制表人',
  shipper: '发货人', carrier: '承运人', driver: '司机', driverName: '司机', vehicleNo: '车牌号',
  transportVehicleNo: '运输车号', contactInfo: '联系方式', driverContactPhone: '司机电话',
  deliveryLocation: '发货地点', toLocation: '去向地点', tenantManager: '承租方负责人', tenantContact: '承租方联系人',
  tenantContactPhone: '承租方电话', rentalStation: '租赁站', note: '备注', remark: '备注',
  name: '名称', personName: '姓名', duty: '职务', billingUnitName: '开票单位', taxNumber: '税号',
  paymentType: '缴费类型', paymentAmount: '登记金额', amount: '金额', totalAmount: '合计金额',
  paymentMethod: '付款方式', paymentName: '付款名称', paidAmount: '已付金额', unpaidAmount: '未付金额',
  rentPrice: '租金单价', rentalPrice: '租金单价', compensationPrice: '赔偿单价', dailyRent: '日租金',
  taxRate: '税率', settlementCycle: '结算周期', contractStartDate: '合同开始日期', contractEndDate: '合同结束日期',
  taskName: '盘点名称', taskId: '盘点编号', expectedQuantity: '账面数量', actualQuantity: '实盘数量',
  countedQuantity: '已盘数量', diffQuantity: '差异数量', uncheckedDays: '未盘天数',
  estimateName: '预估名称', estimateId: '预估编号', estimatedQty: '预估数量', requiredQty: '需求数量',
  shortageQty: '缺口数量', suggestion: '建议', supported: '是否支持', reason: '说明', summary: '概况',
  inventoryBalance: '库存余额', beforeQuantity: '变动前数量', afterQuantity: '变动后数量', changeQuantity: '变动数量',
  behaviorType: '业务类型', totalNum: '全部匹配数', current: '当前页',
  dashboard: '库存概况', kpi: '指标', anomalySummary: '异常种类', taskList: '盘点任务', anomalyList: '库存异常',
  totalAnomalyMaterials: '异常材料种类', negativeInventoryCount: '负库存材料种类', negativeRentedCount: '在租为负种类',
  negativeLeasedCount: '租入未退为负种类', stagnantCount: '长期无流水种类',
  systemInventoryQty: '账面库存', countedQty: '实盘数量', diffQty: '差异数量', expectedQty: '账面数量',
  totalItems: '材料种类', countedItems: '已盘种类', diffItems: '差异种类', diffAmount: '差异金额', diffRate: '差异率',
  siteCount: '工地数量', materialCount: '材料种类', anomalySiteCount: '异常工地数', uncheckedSiteCount: '未盘点工地数',
  diffMaterialCount: '差异材料种类', rentQuantity: '租出数量', compensationQuantity: '赔偿数量', latestCountedQty: '最近实盘数量',
  eventDate: '业务日期', behaviorName: '业务名称', delta: '变动数量', beforeExpected: '变动前账面数量', afterExpected: '变动后账面数量',
  statusName: '状态', documentOwner: '单据负责人', rentSideOwner: '租出负责人', returnSideOwner: '归还负责人',
  categories: '分类', materials: '材料', ladders: '阶梯价', contractPricingUnit: '合同计价单位',
  compensationUnitPrice: '赔偿单价', predictRentNumber: '预计租赁数量', segmentPriceSummary: '阶梯价概况',
  loadingFeeUnitPrice: '装车费单价', unloadingFeeUnitPrice: '卸车费单价', freightUnitPrice: '运费单价', packingFeeUnitPrice: '打包费单价',
  miscFeeDetails: '杂费明细', majorCategoryName: '杂费大类', miscfeeCategoryName: '杂费名称', unitPrice: '单价',
  billingUnits: '开票单位', progress: '进度', predictQuantity: '预计数量', reportBriefInfos: '报告列表', reportData: '报告内容',
  reportId: '报告编号', periodType: '报告周期', settlementPeriodList: '结算账期', firstBusinessDate: '首笔业务日期', latestBusinessDate: '最近业务日期',
  reconciliationDocumentName: '物料对账单', reconciliationDate: '对账日期', warehouseName: '仓库', timePeriod: '时间范围',
  actualAmount: '登记金额', allocatedAmount: '已分配核销金额', unallocatedAmount: '未分配金额',
  documentBriefInfoList: '单据列表', personnelInfos: '人员列表', transportInfos: '运输资料', materialInfos: '材料明细',
  inventoryUnit: '库存单位', rentedQuantity: '在租数量', leasedQuantity: '租入未退数量', totalQuantity: '总量',
  procurementInboundQuantity: '采购入库数量', lastFlowDate: '最近流水日期', rentalUnit: '计价单位',
  dailyRentV2: '日租金', materialValue: '材料价值', minRentDays: '最少租赁天数',
  shortageItems: '缺口材料', sufficientItems: '库存充足材料', recommendations: '推荐站点',
  demandQty: '需求数量', gapQty: '缺口数量', recommendReason: '推荐说明', inventoryMatch: '库存匹配',
  enoughCount: '充足材料种类', shortageCount: '缺货材料种类', buildingArea: '建筑面积',
  floorAbove: '地上层数', floorBelow: '地下层数', buildingHeight: '建筑高度', rentalDays: '租赁天数',
  region: '地区', scopeSummary: '估算范围', scopeNotices: '范围说明', calculationSummary: '计算说明',
  thisRentFee: '本期租金', thisCompensationFee: '本期赔偿', thisTotalFee: '本期合计金额',
  thisMiscFee: '本期杂费', accumulatedRentFee: '累计租金', accumulatedTotalFee: '累计合计金额',
  rentFeeInfos: '租金明细', compensationFeeInfos: '赔偿明细', incidentalFeeInfos: '杂费明细',
  list: '查询结果', items: '明细', records: '记录', materialInfoList: '材料明细', materialRentInfoList: '租出材料',
  materialReturnInfoList: '归还材料', materialCompensationInfoList: '赔偿材料', materialRentInInfoList: '租入材料',
  materialRentInReturnInfoList: '退租材料', paymentList: '登记收付款', personnelInfoList: '人员',
  incidentalList: '杂费', personnelInfo: '人员信息', relatedDocuments: '关联单据',
  rentOutProgress: '租出进度', returnProgress: '归还进度', rentInProgress: '租入进度', rentInReturnProgress: '退租进度',
};

export const businessValue = (key, value) => {
  if (value === null || value === undefined || value === '') return '未填写';
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (key === 'reviewStatus') return ({ 0: '未复核', 1: '已复核' })[String(value)] || '状态待核实';
  if (key === 'projectBusinessType') return ({ rent_out: '租出', rent_in: '租入' })[value] || String(value);
  if (typeof value === 'object') return '';
  return String(value);
};

// Copy only accepted fields; a model cannot supply a route, arbitrary URL or executable action.
export const BUSINESS_PAGES = Object.freeze({
  PROJECT_WORKFLOW: ['2-1', '项目业务页面'],
  PROJECT_RENT_OUT: ['2-1', '租出项目'], PROJECT_RENT_IN: ['2-2', '租入项目'],
  INVENTORY: ['3-0', '库存工作台'], PROCUREMENT: ['3-1', '采购入库'], INBOUND: ['3-2', '其他入库'],
  OUTBOUND: ['3-3', '其他出库'], WAREHOUSE_COUNT: ['3-4', '仓库盘点'], TEMPORARY_STORAGE: ['3-5', '暂存'],
  TEMPORARY_RETURN: ['3-6', '退还'], SITE_DISTRIBUTION: ['3-7', '工地材料分布'],
  MATERIALS: ['4-1', '材料管理'], PERSONNEL: ['4-4', '人员管理'], BILLING_UNITS: ['4-6', '开票单位'],
  ESTIMATE: ['5-2', '材料预估'], MARKET: ['5-3', '材料商城'], HELP: ['help', '帮助中心'],
});
export const normalizeBusinessNavigation = (input) => {
  if (!input || typeof input !== 'object' || !Object.prototype.hasOwnProperty.call(BUSINESS_PAGES, input.target)) return null;
  const result = { target: input.target, menuKey: BUSINESS_PAGES[input.target][0], filters: {} };
  if (input.target === 'PROJECT_WORKFLOW') {
    const projectId = input.filters?.projectId;
    const workflow = input.filters?.workflow || 'overview';
    if (typeof projectId !== 'string' || !/^[a-zA-Z0-9_-]{1,100}$/.test(projectId)) return null;
    if (!['overview', 'contract', 'projectMaterial', 'rentOut', 'returnOrder', 'compensationOrder', 'rentIn', 'rentInReturn', 'materialReconciliation', 'customerPayment', 'financeCheck'].includes(workflow)) return null;
    result.filters = { projectId, workflow };
  }
  if (input.target === 'MARKET') {
    ['keyword', 'city', 'categoryName', 'materialSpecification', 'countingUnit', 'marketPriceUnit', 'stationCid'].forEach((key) => {
      const value = input.filters?.[key];
      if (typeof value === 'string' && value.length <= 200) result.filters[key] = value;
    });
    if (Number.isFinite(input.filters?.minAvailableQty) && input.filters.minAvailableQty >= 0) result.filters.minAvailableQty = input.filters.minAvailableQty;
    if (input.filters?.sortField === 'PRICE_ASC' && result.filters.countingUnit && result.filters.marketPriceUnit) result.filters.sortField = 'PRICE_ASC';
  }
  return result;
};

export const requestBusinessNavigation = (input) => {
  const safe = normalizeBusinessNavigation(input);
  if (!safe) return false;
  window.dispatchEvent(new CustomEvent('agent:business-navigation', { detail: safe }));
  return true;
};
