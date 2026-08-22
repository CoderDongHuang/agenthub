import { expect, test, type Page } from '@playwright/test'

const consoleErrors: string[] = []

function watchConsole(page: Page) {
  page.on('console', message => {
    if (message.type() === 'error') consoleErrors.push(message.text())
  })
  page.on('pageerror', error => consoleErrors.push(error.message))
}

async function expectNoPageOverflow(page: Page) {
  const sizes = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(sizes.scrollWidth).toBeLessThanOrEqual(sizes.clientWidth)
}

async function loginWithLocalDemoAccount(page: Page) {
  await page.goto('/login')
  await page.getByRole('button', { name: '填入 admin / admin123' }).click()
  await page.getByRole('button', { name: '进入工作台' }).click()
  await expect(page).toHaveURL(/\/console\/dashboard$/)
  await expect(page.getByRole('heading', { name: '工作台' })).toBeVisible()
}

test.describe.serial('AgentMesh live system in Microsoft Edge', () => {
  test.beforeEach(async ({ page }) => {
    consoleErrors.length = 0
    watchConsole(page)
  })

  test('public and authenticated pages remain usable on desktop and mobile', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/')
    await expect(page.locator('h1')).toBeVisible()
    await expectNoPageOverflow(page)

    await page.setViewportSize({ width: 390, height: 844 })
    await page.reload()
    await expect(page.locator('h1')).toBeVisible()
    await expectNoPageOverflow(page)

    await loginWithLocalDemoAccount(page)
    await expectNoPageOverflow(page)
    expect(consoleErrors).toEqual([])
  })

  test('runs the persisted workflow through the real Java and Python workers', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await loginWithLocalDemoAccount(page)
    await page.goto('/console/workflows')
    await expect(page.getByRole('heading', { name: '流程编排' })).toBeVisible()
    await expectNoPageOverflow(page)

    const executionResponse = page.waitForResponse(response =>
      response.request().method() === 'POST' && /\/api\/workspace\/workflow\/\d+\/run$/.test(response.url()))
    await page.getByRole('button', { name: '运行流程' }).click()
    const executionPayload = await (await executionResponse).json()
    console.log(`Live workflow execution ${executionPayload.data?.executionId}`)
    await expect(page.getByText('流程执行完成')).toBeVisible({ timeout: 100_000 })
    await expect(page.locator('.workflow-summary').getByText('completed', { exact: true })).toBeVisible()
    expect(consoleErrors).toEqual([])
  })
})
