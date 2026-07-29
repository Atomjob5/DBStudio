import { describe, expect, it } from "vitest";
import { nextTick } from "vue";
import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import type { ColumnOption } from "../columnFilter";
import ResultSingleRecordView from "./ResultSingleRecordView.vue";

describe("ResultSingleRecordView", () => {
  it("transposes one result row into field, value, remark and type columns", async () => {
    const columns: ColumnOption[] = [
      {
        index: 0, label: "ID", name: "ID", remarks: "客户编号", catalog: "", schema: "",
        table: "CUSTOMERS", typeName: "NUMBER", jdbcType: 2, quotedLabel: '"ID"'
      },
      {
        index: 1, label: "NAME", name: "NAME", remarks: "客户名称", catalog: "", schema: "",
        table: "CUSTOMERS", typeName: "VARCHAR2", jdbcType: 12, quotedLabel: '"NAME"'
      }
    ];
    const wrapper = mount(ResultSingleRecordView, {
      props: { columns, row: { sourceIndex: 3, cells: ["1001", null] } },
      global: { plugins: [ElementPlus] }
    });

    expect(wrapper.findAllComponents({ name: "ElTableColumn" }).map((column) => column.props("label")))
      .toEqual(["字段名", "字段值", "字段备注", "字段类型"]);
    expect(wrapper.findComponent({ name: "ElTable" }).props("data")).toEqual([
      { index: 0, label: "ID", value: "1001", remarks: "客户编号", typeName: "NUMBER" },
      { index: 1, label: "NAME", value: null, remarks: "客户名称", typeName: "VARCHAR2" }
    ]);

    await wrapper.setProps({ columns: [{ ...columns[0], remarks: "客户主键" }, columns[1]] });
    await nextTick();
    expect(wrapper.findComponent({ name: "ElTable" }).props("data")[0]).toMatchObject({ remarks: "客户主键" });
  });
});
