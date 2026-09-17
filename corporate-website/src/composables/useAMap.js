/**
 * 高德地图 JS API 懒加载 + 地理编码 composable。
 *
 * 设计要点：
 * - 使用动态 script 标签加载 AMap JS API，避免在首屏阻塞
 * - 单例模式：全局只加载一次 SDK
 * - 通过环境变量 VITE_AMAP_KEY 或常量 AMAP_KEY 提供 Key
 * - Key 缺失时返回 null，调用方应降级处理
 * - 地理编码使用 AMap.Geocoder，将中文地址转为经纬度
 * - POI 检索使用 AMap.PlaceSearch，按关键词命中高德收录的真实点位（更精确）
 *
 * 使用：
 *   const { load, geocode, placeSearch } = useAMap()
 *   await load()                        // 确保 SDK 已加载
 *   const { lng, lat, name, address } = await placeSearch('浙江利强包装科技有限公司')
 *   const { lng, lat } = await geocode('浙江省温州市...')
 *   const map = new AMap.Map(...)
 */

const AMAP_KEY = import.meta.env.VITE_AMAP_KEY || ''

let loaderPromise = null

export function useAMap() {
  /** 动态加载 AMap JS API，返回 window.AMap 构造函数 */
  function load() {
    if (typeof window === 'undefined') return Promise.reject(new Error('No window'))
    if (window.AMap) return Promise.resolve(window.AMap)
    if (loaderPromise) return loaderPromise

    loaderPromise = new Promise((resolve, reject) => {
      if (!AMAP_KEY) {
        reject(new Error('AMAP_KEY_MISSING'))
        return
      }

      window._AMapSecurityConfig = {
        securityJsCode: import.meta.env.VITE_AMAP_SECURITY_CODE || '',
      }

      const script = document.createElement('script')
      script.src = `https://webapi.amap.com/maps?v=2.0&key=${AMAP_KEY}&plugin=AMap.Geocoder,AMap.Marker,AMap.PlaceSearch`
      script.async = true
      script.onload = () => {
        if (window.AMap) resolve(window.AMap)
        else reject(new Error('AMAP_LOAD_FAILED'))
      }
      script.onerror = () => reject(new Error('AMAP_LOAD_ERROR'))
      document.head.appendChild(script)
    })

    return loaderPromise
  }

  /** 将中文地址地理编码为经纬度 */
  async function geocode(address) {
    const AMap = await load()
    return new Promise((resolve, reject) => {
      const geocoder = new AMap.Geocoder({ city: '全国' })
      geocoder.getLocation(address, (status, result) => {
        if (status === 'complete' && result.geocodes?.length) {
          const loc = result.geocodes[0].location
          resolve({ lng: loc.lng, lat: loc.lat, formattedAddress: result.geocodes[0].formattedAddress })
        } else {
          reject(new Error('GEOCODE_FAILED'))
        }
      })
    })
  }

  /**
   * POI 关键词搜索。优先用公司名/地址做 POI 检索，
   * 命中后拿到的是高德收录的真实点位（与高德网页搜索一致），比地址地理编码更精确。
   */
  function placeSearch(keyword, city = '全国') {
    return load().then((AMap) => {
      return new Promise((resolve, reject) => {
        const search = new AMap.PlaceSearch({ city, pageSize: 5, pageIndex: 1 })
        search.search(keyword, (status, result) => {
          if (status === 'complete' && result?.poiList?.pois?.length) {
            const poi = result.poiList.pois[0]
            resolve({
              lng: poi.location.lng,
              lat: poi.location.lat,
              name: poi.name,
              address: poi.address,
            })
          } else {
            reject(new Error('PLACE_SEARCH_FAILED'))
          }
        })
      })
    })
  }

  return { load, geocode, placeSearch, hasKey: !!AMAP_KEY }
}
