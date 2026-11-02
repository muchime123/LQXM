/* ============================================================
   利强包装 · 静态官网 前端交互脚本
   功能：导航吸顶状态 / 移动端菜单 / Hero 轮播 / 滚动渐入 /
         回到顶部 / 产品筛选 / 复制 / 询盘表单(mailto)
   ============================================================ */
(function () {
  'use strict'

  /* ---------- 工具 ---------- */
  function $all(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)) }
  function on(sel, evt, fn, opts) { $all(sel).forEach(function (el) { el.addEventListener(evt, fn, opts) }) }

  /* ---------- 1. 导航：吸顶 + 内页常驻 solid ---------- */
  var header = document.querySelector('.site-header')
  var isHome = document.body.getAttribute('data-page') === 'home'
  function onScrollHeader() {
    if (!header) return
    var scrolled = window.scrollY > 24
    header.classList.toggle('scrolled', scrolled)
    if (isHome) header.classList.toggle('solid', scrolled)
  }
  if (header) {
    if (!isHome) header.classList.add('solid')
    window.addEventListener('scroll', onScrollHeader, { passive: true })
    onScrollHeader()
  }

  /* ---------- 2. 移动端菜单 ---------- */
  var menuBtn = document.querySelector('.menu-button')
  var desktopNav = document.querySelector('.desktop-nav')
  function closeMobileNav() {
    if (!desktopNav) return
    desktopNav.classList.remove('open')
    document.body.classList.remove('mobile-nav-active')
    if (menuBtn) menuBtn.setAttribute('aria-expanded', 'false')
  }
  function toggleMobileNav() {
    if (!desktopNav) return
    var open = desktopNav.classList.toggle('open')
    document.body.classList.toggle('mobile-nav-active', open)
    if (menuBtn) menuBtn.setAttribute('aria-expanded', String(open))
  }
  if (menuBtn) menuBtn.addEventListener('click', toggleMobileNav)
  $all('.desktop-nav .nav-item, .desktop-nav a, .mobile-nav-cta').forEach(function (el) {
    el.addEventListener('click', closeMobileNav)
  })
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeMobileNav() })

  /* ---------- 3. 首页 Hero 轮播 ---------- */
  var heroEl = document.querySelector('.hero-carousel')
  if (heroEl) {
    var slides = $all('.hero-slide', heroEl)
    var dots = $all('.hero-dot', heroEl)
    var heroCopyInner = document.querySelector('.hero-copy__inner')
    var heroData = window.__HERO || []
    var heroIndex = 0
    var heroTimer = null
    var heroPaused = false
    var transitioning = false
    var total = slides.length

    function renderHeroCopy(i) {
      if (!heroCopyInner || !heroData[i]) return
      var d = heroData[i]
      var html = ''
      if (d.subtitle) html += '<p class="hero-eyebrow">' + d.subtitle + '</p>'
      if (d.title) html += '<h1>' + d.title + '</h1>'
      if (d.description) html += '<p class="hero-description">' + d.description + '</p>'
      html += '<div class="hero-actions">'
      if (d.primaryText) html += '<a class="button primary" href="' + d.primaryUrl + '">' + d.primaryText + ' <span>→</span></a>'
      if (d.secondaryText) html += '<a class="button ghost" href="' + d.secondaryUrl + '">' + d.secondaryText + '</a>'
      html += '</div>'
      heroCopyInner.innerHTML = html
    }

    function gotoHero(i) {
      if (!total || transitioning) return
      transitioning = true
      heroIndex = (i + total) % total
      slides.forEach(function (s, idx) { s.classList.toggle('is-active', idx === heroIndex) })
      dots.forEach(function (d, idx) { d.classList.toggle('is-active', idx === heroIndex) })
      renderHeroCopy(heroIndex)
      setTimeout(function () { transitioning = false }, 700)
    }
    function nextHero() { gotoHero(heroIndex + 1) }
    function prevHero() { gotoHero(heroIndex - 1) }
    function resetHeroTimer() {
      if (heroTimer) clearInterval(heroTimer)
      heroTimer = setInterval(function () {
        if (!document.hidden && !heroPaused) nextHero()
      }, 5200)
    }
    dots.forEach(function (d, idx) {
      d.addEventListener('click', function () { resetHeroTimer(); gotoHero(idx) })
    })
    var navP = $all('.hero-nav--prev', heroEl)[0]
    var navN = $all('.hero-nav--next', heroEl)[0]
    if (navP) navP.addEventListener('click', function () { resetHeroTimer(); prevHero() })
    if (navN) navN.addEventListener('click', function () { resetHeroTimer(); nextHero() })
    heroEl.addEventListener('mouseenter', function () { heroPaused = true })
    heroEl.addEventListener('mouseleave', function () { heroPaused = false })
    renderHeroCopy(0)
    resetHeroTimer()
  }

  /* ---------- 4. 滚动渐入（.reveal → .visible） ---------- */
  if ('IntersectionObserver' in window) {
    var revealObserver = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add('visible')
          revealObserver.unobserve(entry.target)
        }
      })
    }, { threshold: 0.12 })
    $all('.reveal').forEach(function (el) { revealObserver.observe(el) })
  } else {
    $all('.reveal').forEach(function (el) { el.classList.add('visible') })
  }

  /* ---------- 5. 回到顶部 ---------- */
  var btt = document.querySelector('.back-to-top')
  var bttBar = btt ? btt.querySelector('.back-to-top__bar') : null
  var BTT_CIRC = 2 * Math.PI * 21
  function onScrollBtt() {
    if (!btt) return
    var doc = document.documentElement
    var max = doc.scrollHeight - window.innerHeight
    var progress = max > 0 ? Math.min(window.scrollY / max, 1) : 0
    btt.classList.toggle('show', window.scrollY > 320)
    if (bttBar) bttBar.style.strokeDashoffset = String(BTT_CIRC * (1 - progress))
  }
  if (btt) {
    window.addEventListener('scroll', onScrollBtt, { passive: true })
    window.addEventListener('resize', onScrollBtt, { passive: true })
    btt.addEventListener('click', function () { window.scrollTo({ top: 0, behavior: 'smooth' }) })
    onScrollBtt()
  }

  /* ---------- 6. 产品中心：筛选 ---------- */
  var productsGrid = document.getElementById('products-grid')
  var categoryTabs = $all('[data-cat-filter]')
  var searchInput = document.getElementById('product-search')
  var resultCount = document.getElementById('product-count')
  var clearBtn = document.getElementById('clear-filters')
  var extraSelects = $all('[data-select-filter]')

  if (productsGrid) {
    var cards = $all('.catalog-card', productsGrid)
    var state = { category: '', keyword: '', extras: {} }

    function applyFilters() {
      var visible = 0
      cards.forEach(function (card) {
        var ok = true
        if (state.category && card.getAttribute('data-category') !== state.category) ok = false
        if (ok && state.keyword) {
          var hay = (card.getAttribute('data-name') || '') + (card.getAttribute('data-category') || '')
          if (hay.toLowerCase().indexOf(state.keyword.toLowerCase()) === -1) ok = false
        }
        if (ok) {
          for (var key in state.extras) {
            var v = state.extras[key]
            if (v && card.getAttribute('data-' + key) !== v) { ok = false; break }
          }
        }
        card.style.display = ok ? '' : 'none'
        if (ok) visible++
      })
      if (resultCount) resultCount.textContent = String(visible)
      // 空状态
      var empty = document.getElementById('products-empty')
      if (empty) empty.style.display = visible ? 'none' : 'block'
    }

    categoryTabs.forEach(function (tab) {
      tab.addEventListener('click', function () {
        var isAll = tab.getAttribute('data-cat-filter') === ''
        categoryTabs.forEach(function (t) { t.classList.toggle('active', t === tab) })
        state.category = isAll ? '' : tab.getAttribute('data-cat-filter')
        applyFilters()
      })
    })

    if (searchInput) {
      searchInput.addEventListener('input', function () {
        state.keyword = searchInput.value.trim()
        applyFilters()
      })
    }

    extraSelects.forEach(function (sel) {
      sel.addEventListener('change', function () {
        state.extras[sel.getAttribute('data-select-filter')] = sel.value
        applyFilters()
      })
    })

    if (clearBtn) {
      clearBtn.addEventListener('click', function () {
        state = { category: '', keyword: '', extras: {} }
        categoryTabs.forEach(function (t) { t.classList.toggle('active', t.getAttribute('data-cat-filter') === '') })
        if (searchInput) searchInput.value = ''
        extraSelects.forEach(function (s) { s.value = '' })
        applyFilters()
      })
    }

    applyFilters()
  }

  /* ---------- 7. 复制 ---------- */
  function toast(msg) {
    var t = document.getElementById('copy-toast')
    if (!t) {
      t = document.createElement('div')
      t.id = 'copy-toast'
      t.className = 'copy-toast'
      t.setAttribute('role', 'status')
      document.body.appendChild(t)
    }
    t.textContent = msg
    t.classList.add('show')
    clearTimeout(t._timer)
    t._timer = setTimeout(function () { t.classList.remove('show') }, 2200)
  }
  on('[data-copy]', 'click', function (e) {
    var text = e.currentTarget.getAttribute('data-copy') || ''
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(function () { toast('已复制') }).catch(function () { fallbackCopy(text) })
    } else {
      fallbackCopy(text)
    }
  })
  function fallbackCopy(text) {
    var ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    try { document.execCommand('copy'); toast('已复制') } catch (err) { /* ignore */ }
    document.body.removeChild(ta)
  }

  /* ---------- 8. 询盘表单：mailto 组装（静态站无后端） ---------- */
  var inquiryForm = document.getElementById('inquiry-form')
  if (inquiryForm) {
    inquiryForm.addEventListener('submit', function (e) {
      e.preventDefault()
      var f = inquiryForm
      var name = (f.querySelector('[name=name]') || {}).value || ''
      var company = (f.querySelector('[name=company]') || {}).value || ''
      var phone = (f.querySelector('[name=phone]') || {}).value || ''
      var email = (f.querySelector('[name=email]') || {}).value || ''
      var product = (f.querySelector('[name=product]') || {}).value || ''
      var desc = (f.querySelector('[name=description]') || {}).value || ''
      var to = (f.getAttribute('data-mailto') || '')
      if (!desc) { alert('请填写需求描述'); return }
      var body = '联系人：' + name + '\n公司：' + company + '\n电话：' + phone + '\n邮箱：' + email
        + '\n关注产品：' + product + '\n需求描述：' + desc
      var subject = '官网咨询：' + (product || '包装需求') + (name ? '（' + name + '）' : '')
      var mailto = 'mailto:' + to + '?subject=' + encodeURIComponent(subject) + '&body=' + encodeURIComponent(body)
      window.location.href = mailto
      var tip = document.getElementById('inquiry-success')
      if (tip) tip.style.display = 'block'
    })
  }

  /* ---------- 9. 产品详情图库缩略图 ---------- */
  $all('.detail-thumbnail').forEach(function (thumb) {
    thumb.addEventListener('click', function () {
      var idx = thumb.getAttribute('data-index')
      $all('.detail-thumbnail').forEach(function (t) { t.classList.toggle('active', t === thumb) })
      var main = document.querySelector('.detail-main-image img')
      var src = thumb.getAttribute('data-src')
      if (main && src) main.src = src
      var _unused = idx
    })
  })
})()
