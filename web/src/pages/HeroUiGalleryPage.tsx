import {
  Button,
  Card,
  Checkbox,
  Chip,
  Input,
  Label,
  ListBox,
  Modal,
  Drawer,
  Pagination,
  Select,
  Switch,
  Table,
  Tabs,
  Tooltip,
} from "@heroui/react";

export function HeroUiGalleryPage() {
  const overlay = new URLSearchParams(window.location.search).get("overlay");
  return (
    <main className={`heroui-gallery${overlay ? " heroui-gallery--overlay" : ""}`} data-testid="heroui-gallery">
      <header className="heroui-gallery-header">
        <p className="heroui-gallery-eyebrow">Finance Tracker · HeroUI v3</p>
        <h1>HeroUI Gallery</h1>
      </header>

      <section className="heroui-gallery-section" aria-labelledby="hero-button-title">
        <h2 id="hero-button-title">Button</h2>
        <div className="heroui-gallery-row">
          <Button>Default</Button>
          <Button variant="primary">Primary</Button>
          <Button variant="secondary">Secondary</Button>
          <Button variant="tertiary">Tertiary</Button>
          <Button isDisabled>Disabled</Button>
        </div>
      </section>

      <section className="heroui-gallery-section" aria-labelledby="hero-field-title">
        <h2 id="hero-field-title">Fields</h2>
        <div className="heroui-gallery-grid">
          <label className="heroui-gallery-field">Account name<Input placeholder="Enter account" /></label>
          <Select aria-label="Account">
            <Select.Trigger><Select.Value /></Select.Trigger>
            <Select.Popover><ListBox><ListBox.Item id="cash">Cash account</ListBox.Item><ListBox.Item id="brokerage">Investment account</ListBox.Item></ListBox></Select.Popover>
          </Select>
          <Checkbox>
            <Checkbox.Content>
              <Checkbox.Control><Checkbox.Indicator /></Checkbox.Control>
              <Label>Include pending records</Label>
            </Checkbox.Content>
          </Checkbox>
          <Switch>
            <Switch.Content>
              <Switch.Control><Switch.Thumb /></Switch.Control>
              <Label>Show archived accounts</Label>
            </Switch.Content>
          </Switch>
        </div>
      </section>

      <section className="heroui-gallery-section" aria-labelledby="hero-surface-title">
        <h2 id="hero-surface-title">Surfaces and status</h2>
        <div className="heroui-gallery-grid">
          <Card>
            <Card.Content>
              <strong>Cash balance</strong>
              <span>¥ 12,480.00</span>
            </Card.Content>
          </Card>
          <div className="heroui-gallery-row">
            <Chip color="accent">Ready</Chip>
            <Chip color="danger">Error</Chip>
            <Chip>Neutral</Chip>
          </div>
        </div>
      </section>

      <section className="heroui-gallery-section" aria-labelledby="hero-tabs-title">
        <h2 id="hero-tabs-title">Tabs</h2>
        <Tabs aria-label="Ledger views" className="heroui-gallery-tabs" defaultSelectedKey="cash">
          <Tabs.List>
            <Tabs.Tab id="cash">Cash ledger</Tabs.Tab>
            <Tabs.Tab id="investment">Investment ledger</Tabs.Tab>
          </Tabs.List>
          <Tabs.Panel id="cash">Cash records</Tabs.Panel>
          <Tabs.Panel id="investment">Investment records</Tabs.Panel>
        </Tabs>
      </section>

      <section className="heroui-gallery-section" aria-labelledby="hero-table-title">
        <h2 id="hero-table-title">Table and pagination</h2>
        <div className="heroui-gallery-table">
          <Table aria-label="Cash records">
            <Table.Content>
              <Table.Header>
                <Table.Column id="date" isRowHeader>日期</Table.Column>
                <Table.Column id="account">账户</Table.Column>
                <Table.Column id="amount">金额</Table.Column>
              </Table.Header>
              <Table.Body>
                <Table.Row id="record-1">
                  <Table.Cell>2026-09-29</Table.Cell>
                  <Table.Cell>日常账户</Table.Cell>
                  <Table.Cell>-12.50 CNY</Table.Cell>
                </Table.Row>
                <Table.Row id="record-2">
                  <Table.Cell>2026-09-28</Table.Cell>
                  <Table.Cell>工资账户</Table.Cell>
                  <Table.Cell>+12,480.00 CNY</Table.Cell>
                </Table.Row>
              </Table.Body>
            </Table.Content>
          </Table>
          <Pagination aria-label="Pages">
            <Pagination.Content>
              <Pagination.Item><Pagination.Previous aria-label="上一页">上一页</Pagination.Previous></Pagination.Item>
              <Pagination.Item><Pagination.Link isActive>1</Pagination.Link></Pagination.Item>
              <Pagination.Item><Pagination.Link>2</Pagination.Link></Pagination.Item>
              <Pagination.Item><Pagination.Next aria-label="下一页">下一页</Pagination.Next></Pagination.Item>
            </Pagination.Content>
          </Pagination>
        </div>
      </section>

      <section className="heroui-gallery-section" aria-labelledby="hero-overlay-title">
        <h2 id="hero-overlay-title">Overlay controls</h2>
        <div className="heroui-gallery-row">
          <Tooltip isOpen={overlay === "tooltip"}>
            <Tooltip.Trigger><Button variant="secondary">Tooltip</Button></Tooltip.Trigger>
            <Tooltip.Content>查看收支详情</Tooltip.Content>
          </Tooltip>
          <Modal isOpen={overlay === "modal"}>
            <Modal.Trigger><Button variant="secondary">Modal</Button></Modal.Trigger>
            <Modal.Backdrop>
              <Modal.Container>
                <Modal.Dialog>
                  <Modal.Header><Modal.Heading>确认操作</Modal.Heading></Modal.Header>
                  <Modal.Body>确认删除这条收支记录？</Modal.Body>
                  <Modal.Footer><Modal.CloseTrigger>取消</Modal.CloseTrigger></Modal.Footer>
                </Modal.Dialog>
              </Modal.Container>
            </Modal.Backdrop>
          </Modal>
          <Drawer isOpen={overlay === "drawer"}>
            <Drawer.Trigger>Drawer</Drawer.Trigger>
            <Drawer.Backdrop>
              <Drawer.Content placement="right">
                <Drawer.Dialog>
                  <Drawer.Header><Drawer.Heading>收支详情</Drawer.Heading></Drawer.Header>
                  <Drawer.Body>视觉核对商户 · -12.50 CNY</Drawer.Body>
                  <Drawer.Footer><Drawer.CloseTrigger>关闭</Drawer.CloseTrigger></Drawer.Footer>
                </Drawer.Dialog>
              </Drawer.Content>
            </Drawer.Backdrop>
          </Drawer>
        </div>
      </section>
    </main>
  );
}
